package pro.perfectproduct.cramin.ingest

import pro.perfectproduct.cramin.util.useCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.dankito.readability4j.Readability4J
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import pro.perfectproduct.cramin.data.db.DocumentEntity
import pro.perfectproduct.cramin.data.repo.DocumentFiles
import pro.perfectproduct.cramin.pipeline.ErrorCode
import pro.perfectproduct.cramin.pipeline.PipelineException
import pro.perfectproduct.cramin.util.Log
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Статья по ссылке (SPEC §7.1): OkHttp GET с User-Agent мобильного браузера, таймаут 30 с,
 * редиректы включены; Markdown/plain text сохраняются, HTML → Readability4J. Меньше 200 символов — ошибка.
 */
class ArticleExtractor(http: OkHttpClient) : SourceExtractor {
    private val http = http.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    override suspend fun extract(document: DocumentEntity, files: DocumentFiles): Extracted = withContext(Dispatchers.IO) {
        val url = UrlClassifier.normalize(document.sourceRef)
        val result = try {
            val request = Request.Builder().url(url)
                .header("User-Agent", MOBILE_USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en,ru;q=0.8,he;q=0.7")
                .build()
            http.newCall(request).useCancellable { resp ->
                if (!resp.isSuccessful) throw PipelineException(ErrorCode.ARTICLE_EXTRACT, "HTTP ${resp.code}")
                val body = resp.body
                val mediaType = body.contentType()
                // ResponseBody.string() honors the declared charset/BOM, falling back to UTF-8.
                val text = body.string()
                if (mediaType?.type == "text" && mediaType.subtype in setOf("markdown", "plain")) {
                    if (text.length < MIN_CHARS) throw PipelineException(ErrorCode.ARTICLE_EXTRACT, "extracted ${text.length} chars")
                    Extracted.Text(text = text, title = null, langHint = null)
                } else {
                    parse(url, text)
                }
            }
        } catch (e: IOException) {
            throw PipelineException(ErrorCode.NETWORK, e.javaClass.simpleName, e)
        } catch (e: IllegalArgumentException) {
            throw PipelineException(ErrorCode.ARTICLE_EXTRACT, "bad url", e)
        }
        Log.i(TAG, "doc=${document.id} article: ${result.text.length} chars, paragraphs=${result.text.count { it == '\n' } / 2 + 1}")
        result
    }

    companion object {
        private const val TAG = "Article"
        const val MIN_CHARS = 200
        const val MOBILE_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"

        /** Разбор HTML без сети (тестируется на фикстурах). */
        fun parse(url: String, html: String): Extracted.Text {
            val article = runCatching { Readability4J(url, html).parse() }.getOrNull()
                ?: throw PipelineException(ErrorCode.ARTICLE_EXTRACT, "readability failed")
            val content = article.articleContent
            val paragraphs = if (content != null) HtmlText.paragraphs(content) else emptyList()
            val text = paragraphs.joinToString("\n\n")
            if (text.length < MIN_CHARS) throw PipelineException(ErrorCode.ARTICLE_EXTRACT, "extracted ${text.length} chars")
            return Extracted.Text(text = text, title = article.title?.trim()?.takeIf { it.isNotEmpty() }, langHint = null)
        }
    }
}

/** Текст блочных элементов как абзацы: заголовки, параграфы, пункты списков, цитаты. */
object HtmlText {
    private val BLOCKS = setOf("p", "h1", "h2", "h3", "h4", "h5", "h6", "li", "blockquote", "pre", "td", "th", "dd", "dt", "figcaption")
    private val CONTAINERS = setOf("div", "section", "article", "main", "body", "ul", "ol", "table", "tbody", "tr", "dl", "figure", "aside", "header", "footer", "nav", "span")

    fun paragraphs(root: Element): List<String> {
        val out = ArrayList<String>()
        walk(root, out)
        return out.map { it.replace(Regex("\\s+"), " ").trim() }.filter { it.isNotEmpty() }
    }

    private fun walk(el: Element, out: MutableList<String>) {
        for (node in el.childNodes()) {
            when (node) {
                is TextNode -> if (node.text().isNotBlank()) out += node.text()
                is Element -> {
                    val tag = node.tagName().lowercase()
                    when {
                        tag in BLOCKS && !hasBlockDescendant(node) -> out += node.text()
                        tag in BLOCKS || tag in CONTAINERS -> walk(node, out)
                        tag == "br" -> Unit
                        else -> if (node.text().isNotBlank()) out += node.text()
                    }
                }
                else -> Unit
            }
        }
    }

    private fun hasBlockDescendant(el: Element): Boolean = el.allElements.any { it !== el && it.tagName().lowercase() in BLOCKS }
}
