package pro.perfectproduct.cramin.util

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

object Hashing {
    fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    fun sha256Hex(input: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
        return md.digest().toHex()
    }

    fun sha256Hex(file: File): String = file.inputStream().use { sha256Hex(it) }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
