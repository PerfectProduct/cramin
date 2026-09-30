package pro.perfectproduct.cramin.app

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import pro.perfectproduct.cramin.util.Log

/** Только debug: HttpLoggingInterceptor уровня BASIC, заголовок Authorization редактируется (SPEC §11). */
object HttpLogging {
    fun install(builder: OkHttpClient.Builder) {
        val interceptor = HttpLoggingInterceptor { message -> Log.d("Http", message) }
        interceptor.level = HttpLoggingInterceptor.Level.BASIC
        interceptor.redactHeader("Authorization")
        builder.addInterceptor(interceptor)
    }
}
