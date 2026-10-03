package pro.perfectproduct.cramin.live

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Test
import pro.perfectproduct.cramin.llm.*
import pro.perfectproduct.cramin.pipeline.BriefRequestFactory
import pro.perfectproduct.cramin.util.Lang
import java.io.File
import java.util.concurrent.TimeUnit

/** Opt-in, two-call maximum ledger; never run as part of ordinary live suites. */
class BriefDiagnosticProbeTest {
    @Test fun probe() = runBlocking {
        val directory = System.getenv("CRAMIN_BRIEF_PROBE_DIR")
        assumeTrue("Requires explicit approved diagnostic directory", directory != null)
        val dir = File(requireNotNull(directory))
        val mode = System.getenv("CRAMIN_BRIEF_PROBE_MODE") ?: "dry"
        require(mode in setOf("dry", "dry-long", "short", "long"))
        val key = LiveEnv.requireKey()
        val catalogText = File(dir, "catalog.json").readText()
        val catalog = CatalogSnapshot(ModelCatalog.parse(catalogText), 0)
        val config = ModelConfigResolver.resolve(null, null, LiveEnv.repoConfig, catalog)
        val model = requireNotNull(catalog.find(config.role(ModelRole.BRIEF).model))
        val text = "У реки стоял старый дом. На берегу дети нашли ключ от мастерской. Мастер открыл замок и вернулся к работе."
        val input = if (mode.endsWith("long")) List(60) { text } else listOf(text)
        val request = BriefRequestFactory.build(input, Lang.RU, Lang.EN, config, catalog).copy(
            parametersFrozen = true, supportedParameters = model.supportedParameters.toSet(),
            configOrigin = ConfigOrigin.NEW_SNAPSHOT)
        val body = ChatRequestBody.build(request, request.supportedParameters, true)
        val caps = Json.parseToJsonElement(File(dir, "price-caps.json").readText()).jsonObject
        require(System.currentTimeMillis() - caps.getValue("observedAtEpochMs").jsonPrimitive.long < 3600000)
        val inputCap = caps.getValue("inputPerToken").jsonPrimitive.double
        val outputCap = caps.getValue("outputPerToken").jsonPrimitive.double
        val estimated = (body.toString().toByteArray(Charsets.UTF_8).size + 4096).toLong()
        val bound = 1.25 * (estimated * inputCap + requireNotNull(request.maxTokens) * outputCap)
        require(bound.isFinite() && bound > 0 && bound <= .10)
        val prior = (1..2).map { File(dir, "call-$it-reservation.json") }.filter { it.exists() }
        val reserved = prior.sumOf { Json.parseToJsonElement(it.readText()).jsonObject.getValue("upperUsd").jsonPrimitive.double }
        require(prior.size < 2 && reserved + bound <= .10) { "Approval call/budget limit reached" }
        val index = prior.size + 1
        val codec = Json { encodeDefaults = true }
        val event = RequestDiagnostic.capture(request, body)
        val preflight = buildJsonObject {
            put("mode", mode); put("callsReservedBefore", prior.size); put("upperUsd", bound)
            put("reservedTotalUpperUsd", reserved + bound); put("inputUpperProxy", estimated)
            put("inputPerTokenCap", inputCap); put("outputPerTokenCap", outputCap)
            put("request", codec.encodeToJsonElement(RequestDiagnostic.serializer(), event))
        }
        File(dir, "preflight-$mode.json").writeText(preflight.toString())
        if (mode.startsWith("dry")) return@runBlocking
        val reservation = File(dir, "call-$index-reservation.json")
        check(reservation.createNewFile()) { "Attempt already reserved" }
        reservation.writeText(preflight.toString()) // reserve before HTTP, including uncertain/interrupted calls
        var responseEvent: RequestDiagnostic? = null
        var networkAttempts = 0
        val http = OkHttpClient.Builder().retryOnConnectionFailure(false).followRedirects(false)
            .followSslRedirects(false).callTimeout(180, TimeUnit.SECONDS).readTimeout(150, TimeUnit.SECONDS)
            .addNetworkInterceptor { chain ->
                check(++networkAttempts <= 1) { "No automatic HTTP repeats allowed" }
                val response = chain.proceed(chain.request())
                val root = runCatching { Json.parseToJsonElement(response.peekBody(65536).string()) as? JsonObject }.getOrNull()
                responseEvent = event.response(response.code, root, response.header("X-Request-Id"), null)
                response
            }.build()
        val client = OpenRouterClient(http, { key }, maxAttempts = 1)
        val result = try {
            val response = client.complete(request.copy(onFailureDiagnostic = { responseEvent = it }))
            val valid = runCatching { LlmJson.strict.decodeFromString<Brief>(response.content) }.getOrNull()
            buildJsonObject {
                put("outcome", "RESPONSE"); put("briefParsed", valid != null); put("titleNonBlank", valid?.title?.isNotBlank() == true)
                put("glossaryEntries", valid?.glossary?.size ?: 0)
                put("finishReason", if (response.finishReason in setOf("stop", "length", "error", "content_filter")) response.finishReason else "UNKNOWN")
                put("promptTokens", response.usage.promptTokens); put("completionTokens", response.usage.completionTokens)
                put("actualCostUsd", response.usage.costUsd?.let { JsonPrimitive(it) } ?: JsonNull)
            }
        } catch (e: LlmException) {
            buildJsonObject {
                put("outcome", "REJECTED_OR_TRANSPORT_FAILURE")
                put("exceptionKind", e.javaClass.simpleName)
                put("actualCostUsd", JsonNull)
                put("event", responseEvent?.let { codec.encodeToJsonElement(RequestDiagnostic.serializer(), it) } ?: JsonNull)
            }
        } catch (_: Exception) {
            buildJsonObject { put("outcome", "LOCAL_FAILURE_OR_UNKNOWN"); put("actualCostUsd", JsonNull) }
        }
        val saved = JsonObject(result + mapOf(
            "networkAttempts" to JsonPrimitive(networkAttempts),
            "requestOrResponseEvent" to (responseEvent?.let { codec.encodeToJsonElement(RequestDiagnostic.serializer(), it) } ?: JsonNull)))
        File(dir, "call-$index-result.json").writeText(saved.toString())
        println("BRIEF diagnostic call $index saved; no response content logged")
    }
}
