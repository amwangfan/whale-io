package com.nova.appmonitor.net

import android.util.Log
import com.nova.appmonitor.model.FgAppInfo
import org.json.JSONObject
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Stub local HTTP feed for later cloud / leaf5-style telemetry.
 * GET /v1/fg -> current foreground JSON
 * Not started by default; call start(port) when needed.
 */
class LocalFeedStub {
    private val running = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor()
    private var server: ServerSocketChannel? = null
    private val clients = CopyOnWriteArrayList<SocketChannel>()

    @Volatile var current: FgAppInfo? = null

    fun start(port: Int = DEFAULT_PORT) {
        if (!running.compareAndSet(false, true)) return
        executor.execute {
            try {
                server = ServerSocketChannel.open().apply {
                    configureBlocking(true)
                    socket().reuseAddress = true
                    bind(InetSocketAddress(port))
                }
                Log.i(TAG, "LocalFeedStub listening on $port")
                while (running.get()) {
                    val client = server?.accept() ?: break
                    handle(client)
                }
            } catch (e: Exception) {
                if (running.get()) Log.w(TAG, "feed stopped: ${e.message}")
            } finally {
                running.set(false)
                try { server?.close() } catch (_: Exception) {}
            }
        }
    }

    fun stop() {
        running.set(false)
        try { server?.close() } catch (_: Exception) {}
        clients.forEach { try { it.close() } catch (_: Exception) {} }
        clients.clear()
    }

    private fun handle(client: SocketChannel) {
        try {
            val buf = ByteBuffer.allocate(1024)
            client.read(buf)
            buf.flip()
            val req = StandardCharsets.UTF_8.decode(buf).toString()
            val body = if (req.contains("GET /v1/fg")) {
                val c = current
                if (c == null) {
                    """{"ok":false,"error":"no_fg"}"""
                } else {
                    JSONObject()
                        .put("ok", true)
                        .put("package", c.packageName)
                        .put("label", c.label)
                        .put("ts", c.timestampMs)
                        .put("source", c.source)
                        .toString()
                }
            } else {
                """{"ok":true,"service":"nova-app-monitor","endpoints":["/v1/fg"]}"""
            }
            val resp = buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("Content-Type: application/json; charset=utf-8\r\n")
                append("Connection: close\r\n")
                append("Content-Length: ${body.toByteArray().size}\r\n")
                append("\r\n")
                append(body)
            }
            client.write(ByteBuffer.wrap(resp.toByteArray(StandardCharsets.UTF_8)))
        } catch (e: Exception) {
            Log.w(TAG, "client: ${e.message}")
        } finally {
            try { client.close() } catch (_: Exception) {}
        }
    }

    companion object {
        const val DEFAULT_PORT = 18796
        private const val TAG = "LocalFeedStub"
    }
}
