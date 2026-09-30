package pro.perfectproduct.cramin.ingest

import java.net.URI

/** Что за ссылка: YouTube (SPEC §7.2: youtube.com, m.youtube.com, youtu.be, music.youtube.com, Shorts) или статья. */
object UrlClassifier {
    private val YOUTUBE_HOSTS = setOf("youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be", "music.youtube.com")
    private val URL_REGEX = Regex("^(https?://)[^\\s]+$", RegexOption.IGNORE_CASE)

    fun isUrl(text: String): Boolean = URL_REGEX.matches(text.trim())

    /** Текст, который целиком является URL, считается ссылкой (SPEC §7.5). */
    fun extractUrl(text: String): String? = text.trim().takeIf { isUrl(it) }

    fun isYoutube(url: String): Boolean {
        val host = runCatching { URI(url.trim()).host?.lowercase() }.getOrNull() ?: return false
        return host in YOUTUBE_HOSTS || host.endsWith(".youtube.com")
    }

    /** Нормализует адрес: добавляет схему, если пользователь ввёл «example.com/page». */
    fun normalize(input: String): String {
        val t = input.trim()
        return if (t.startsWith("http://") || t.startsWith("https://")) t else "https://$t"
    }
}
