package com.ciontek.HardwareDemo

import android.util.Base64
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * Minimal from-scratch WebSocket server (RFC 6455) for streaming this app's real RX/TX MDB
 * traffic to a PC viewer live, no simulation involved \u2014 every line broadcast here is the exact
 * same line [MdbSlaveActivity.appendLine] puts on the on-screen log, at the same moment.
 *
 * No third-party dependency: just a raw [ServerSocket], the standard WebSocket opening handshake
 * (SHA-1 + base64 of the client's key), and hand-written text-frame encoding. Intended to be
 * reached via `adb forward tcp:8765 tcp:8765` from a PC connected over USB, so the phone needs no
 * Wi-Fi/LAN configuration at all \u2014 the viewer just connects to ws://localhost:8765.
 */
object LogServer {

    private const val TAG = "LogServer"
    private const val PORT = 8765
    private const val WS_MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"

    private var serverSocket: ServerSocket? = null
    private val clients = CopyOnWriteArrayList<Socket>()

    /** Starts accepting WebSocket connections in the background. Safe to call more than once. */
    fun start() {
        if (serverSocket != null) return
        try {
            val server = ServerSocket(PORT)
            serverSocket = server
            thread(name = "LogServerAccept") { acceptLoop(server) }
            Log.d(TAG, "Listening on port $PORT")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start on port $PORT", t)
        }
    }

    fun stop() {
        try {
            serverSocket?.close()
        } catch (_: Throwable) {
        }
        serverSocket = null
        clients.forEach { closeQuietly(it) }
        clients.clear()
    }

    /** Sends [line] to every currently connected viewer. A no-op if nobody is connected. Safe to
     * call from the UI thread \u2014 the actual socket writes happen on a background thread, same
     * reasoning as [MqttBridge.publishLog]. */
    fun broadcast(line: String) {
        if (clients.isEmpty()) return
        thread(name = "LogServerBroadcast") {
            val frame = encodeTextFrame(line)
            for (socket in clients) {
                try {
                    writeAndFlush(socket, frame)
                } catch (_: Throwable) {
                    clients.remove(socket)
                    closeQuietly(socket)
                }
            }
        }
    }

    private fun acceptLoop(server: ServerSocket) {
        while (!server.isClosed) {
            val socket = try {
                server.accept()
            } catch (_: Throwable) {
                break // server socket was closed via stop()
            }
            thread(name = "LogServerHandshake") { handshake(socket) }
        }
    }

    /** Reads the HTTP upgrade request, replies with the WebSocket 101 handshake, then keeps the
     * socket open for [broadcast] to write to \u2014 this thread just blocks reading (and discarding)
     * whatever the client sends, so a dead connection is noticed and cleaned up. */
    private fun handshake(socket: Socket) {
        try {
            val input = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.US_ASCII))
            var key: String? = null
            while (true) {
                val line = input.readLine() ?: return
                if (line.isEmpty()) break
                val parts = line.split(":", limit = 2)
                if (parts.size == 2 && parts[0].trim().equals("Sec-WebSocket-Key", ignoreCase = true)) {
                    key = parts[1].trim()
                }
            }
            if (key == null) return

            val accept = Base64.encodeToString(
                MessageDigest.getInstance("SHA-1").digest((key + WS_MAGIC).toByteArray(Charsets.UTF_8)),
                Base64.NO_WRAP
            )
            val response = "HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: $accept\r\n\r\n"
            writeAndFlush(socket, response.toByteArray(Charsets.US_ASCII))

            clients.add(socket)
            broadcastSingle(socket, "-- connected to CM30 live log --")

            // Block here just to detect disconnects; incoming client frames aren't needed.
            val buffer = ByteArray(256)
            while (socket.getInputStream().read(buffer) >= 0) { /* discard */ }
        } catch (_: Throwable) {
        } finally {
            clients.remove(socket)
            closeQuietly(socket)
        }
    }

    private fun broadcastSingle(socket: Socket, line: String) {
        try {
            writeAndFlush(socket, encodeTextFrame(line))
        } catch (_: Throwable) {
        }
    }

    /** Encodes [text] as a single unmasked, final WebSocket text frame (server->client frames
     * are never masked per RFC 6455). */
    private fun encodeTextFrame(text: String): ByteArray {
        val payload = text.toByteArray(Charsets.UTF_8)
        val header = when {
            payload.size <= 125 -> byteArrayOf(0x81.toByte(), payload.size.toByte())
            payload.size <= 0xFFFF -> byteArrayOf(
                0x81.toByte(), 126,
                (payload.size ushr 8).toByte(), payload.size.toByte()
            )
            else -> {
                val lenBytes = ByteArray(8)
                var len = payload.size.toLong()
                for (i in 7 downTo 0) {
                    lenBytes[i] = (len and 0xFF).toByte()
                    len = len ushr 8
                }
                byteArrayOf(0x81.toByte(), 127) + lenBytes
            }
        }
        return header + payload
    }

    private fun closeQuietly(socket: Socket) {
        try {
            socket.close()
        } catch (_: Throwable) {
        }
    }

    private fun writeAndFlush(socket: Socket, bytes: ByteArray) {
        val out: OutputStream = socket.getOutputStream()
        out.write(bytes, 0, bytes.size)
        out.flush()
    }
}
