package pro.perfectproduct.cramin.app

import okhttp3.OkHttpClient

/** Release: HTTP-логирования нет вообще (SPEC §11); зависимость logging-interceptor сюда не попадает. */
object HttpLogging {
    @Suppress("UNUSED_PARAMETER")
    fun install(builder: OkHttpClient.Builder) = Unit
}
