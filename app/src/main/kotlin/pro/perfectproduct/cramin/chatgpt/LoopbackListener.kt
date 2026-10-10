package pro.perfectproduct.cramin.chatgpt

import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.io.Closeable
import java.io.IOException

internal class LoopbackListener(private val synthetic: Boolean = false) : Closeable {
    private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 1000 }
    val redirectUri = "http://127.0.0.1:${server.localPort}/auth/callback"
    val syntheticStart = "http://127.0.0.1:${server.localPort}/synthetic/start"

    /** Bounded HTTP parser; no request logging, reflection, external bind, WebView or custom scheme. */
    fun receive(attempt: OAuthAttempt, wrongState: Boolean = false): String {
        while (System.currentTimeMillis() < attempt.expiresAt) {
            val socket = try { server.accept() } catch (_: SocketTimeoutException) { continue }
            try { socket.use {
                it.soTimeout = 2000
                val input = it.getInputStream()
                val raw = StringBuilder()
                while (!raw.endsWith("\r\n\r\n")) {
                    val b = input.read()
                    check(b != -1 && raw.length < 16_384) { "callback_http" }
                    raw.append(b.toChar())
                }
                val lines = raw.toString().split("\r\n")
                val first = lines.first().split(' ')
                check(first.size == 3 && first[0] == "GET" && first[1].startsWith('/') && !first[1].startsWith("//")) { "callback_http" }
                val host = lines.drop(1).filter { h -> h.startsWith("Host:", true) }
                check(host.size == 1 && host.single().substringAfter(':').trim() == "127.0.0.1:${server.localPort}") { "callback_host" }
                val path = first[1].substringBefore('?')
                val output = it.getOutputStream()
                if (synthetic && path == "/synthetic/start") {
                    val state = if (wrongState) "synthetic-wrong-state" else attempt.state
                    val location = "$redirectUri?error=access_denied&state=$state"
                    output.write(("HTTP/1.1 302 Found\r\nLocation: $location\r\nCache-Control: no-store\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").toByteArray())
                } else if (path == "/auth/callback") {
                    val body = "Callback received. Return to Cramin to see verification status."
                    output.write(("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body").toByteArray())
                    return "http://127.0.0.1:${server.localPort}${first[1]}"
                } else {
                    output.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                }
                output.flush()
            } } catch (_: IOException) {
                // Browsers may open a speculative socket and close it without a request.
                // Discard only this connection; keep the one-time OAuth attempt pending.
            } catch (_: IllegalStateException) {
                // Malformed HTTP is not an OAuth callback. Never reflect its content.
            }
        }
        error("callback_timeout")
    }
    override fun close() { server.close() }
}
