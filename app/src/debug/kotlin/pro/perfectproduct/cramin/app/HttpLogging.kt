package pro.perfectproduct.cramin.app

import okhttp3.OkHttpClient
import pro.perfectproduct.cramin.util.Log

/** Debug allowlist: BASIC would include private source URLs/query parameters. */
object HttpLogging {
    fun install(builder: OkHttpClient.Builder) {
        builder.addInterceptor { chain ->
            try {
                chain.proceed(chain.request()).also { Log.d("Http", "response status=${it.code}") }
            } catch (e: java.io.IOException) {
                Log.d("Http", "transport failed")
                throw e
            }
        }
    }
}
