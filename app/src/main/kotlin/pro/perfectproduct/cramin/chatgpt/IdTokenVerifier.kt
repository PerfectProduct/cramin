package pro.perfectproduct.cramin.chatgpt

import kotlinx.serialization.json.*
import java.math.BigInteger
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

/** Deliberately narrow prototype: RS256 only; unsupported algorithms fail closed. No token-supplied URLs. */
internal object IdTokenVerifier {
    fun verify(token: String, jwks: JsonObject, clientId: String, nonce: String,
               now: Long = System.currentTimeMillis() / 1000): JsonObject {
        val parts = token.split('.')
        check(parts.size == 3 && token.length <= 32_768) { "id_token_format" }
        fun decode(s: String) = Base64.getUrlDecoder().decode(s)
        val header = Json.parseToJsonElement(decode(parts[0]).toString(Charsets.UTF_8)).jsonObject
        check(header.string("alg") == "RS256" && header["crit"] == null) { "id_token_algorithm" }
        val kid = header.string("kid") ?: error("id_token_kid")
        val keys = jwks.getValue("keys").jsonArray.map { it.jsonObject }.filter {
            it.string("kid") == kid && it.string("kty") == "RSA" &&
                (it.string("use") == null || it.string("use") == "sig") &&
                (it.string("alg") == null || it.string("alg") == "RS256")
        }
        check(keys.size == 1) { "id_token_key" }
        val jwk = keys.single()
        val n = BigInteger(1, decode(jwk.string("n") ?: error("id_token_key")))
        val e = BigInteger(1, decode(jwk.string("e") ?: error("id_token_key")))
        check(n.bitLength() >= 2048) { "id_token_key_size" }
        val key = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(n, e))
        check(Signature.getInstance("SHA256withRSA").run {
            initVerify(key); update("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII)); verify(decode(parts[2]))
        }) { "id_token_signature" }
        val claims = Json.parseToJsonElement(decode(parts[1]).toString(Charsets.UTF_8)).jsonObject
        check(claims.string("iss") == ISSUER) { "id_token_issuer" }
        val audiences = when (val aud = claims["aud"]) {
            is JsonArray -> aud.map { it.jsonPrimitive.content }
            is JsonPrimitive -> listOf(aud.content)
            else -> emptyList()
        }
        check(clientId in audiences) { "id_token_audience" }
        if (audiences.size > 1 || claims["azp"] != null) check(claims.string("azp") == clientId) { "id_token_azp" }
        fun time(k: String) = (claims[k] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
        check((time("exp") ?: 0) > now - 5) { "id_token_expired" }
        check((time("iat") ?: error("id_token_iat")) <= now + 5) { "id_token_iat" }
        check(claims["nbf"] == null || (time("nbf") ?: Long.MAX_VALUE) <= now + 5) { "id_token_nbf" }
        check(claims.string("nonce") == nonce) { "id_token_nonce" }
        check(!claims.string("sub").isNullOrBlank()) { "id_token_subject" }
        return claims
    }
}
