package com.v2ray.ang.service

import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.SteamDlManager
import com.v2ray.ang.util.LogUtil
import java.io.ByteArrayOutputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Local loopback SOCKS5 bridge consumed by the SteamDL routing rules.
 *
 *  - CONNECT :80  -> streams HTTP requests to the cache server with the Host
 *    header rewritten to the cache domain and Real-Host / Auth-Token headers
 *    added (same wire format as the official SteamDL client and its
 *    mitmproxy addon).
 *  - CONNECT :443 -> raw TCP passthrough to cache:443 (mirrors the official
 *    client's transparent proxy).
 *
 * Xray reaches it through 127.0.0.1:PORT_STEAMDL_BRIDGE; it runs in the app
 * process alongside the VPN service.
 */
object SteamDlBridge {
    private const val TAG = "SteamDlBridge"
    private const val MAX_HEADER_SIZE = 64 * 1024
    private const val CONNECT_TIMEOUT_MS = 10_000

    @Volatile
    private var running = false
    @Volatile
    private var serverSocket: ServerSocket? = null
    @Volatile
    private var runtime: SteamDlManager.Runtime? = null
    @Volatile
    private var pool: ExecutorService? = null
    private val openSockets = ConcurrentHashMap.newKeySet<Socket>()

    fun start(rt: SteamDlManager.Runtime): Boolean {
        if (running) {
            return true
        }
        return try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(AppConfig.LOOPBACK, AppConfig.PORT_STEAMDL_BRIDGE))
            runtime = rt
            serverSocket = ss
            val executor: ExecutorService = Executors.newCachedThreadPool { r ->
                Thread(r, "SteamDlBridge").apply { isDaemon = true }
            }
            pool = executor
            running = true
            executor.execute { acceptLoop(ss) }
            LogUtil.i(TAG, "Bridge listening on ${AppConfig.LOOPBACK}:${AppConfig.PORT_STEAMDL_BRIDGE} -> ${rt.cacheIp}")
            true
        } catch (e: Exception) {
            LogUtil.e(TAG, "Failed to start SteamDL bridge", e)
            stop()
            false
        }
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        openSockets.forEach { s ->
            try {
                s.close()
            } catch (_: Exception) {
            }
        }
        openSockets.clear()
        pool?.shutdownNow()
        pool = null
        runtime = null
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running) {
            try {
                val client = ss.accept()
                client.tcpNoDelay = true
                openSockets.add(client)
                val executor = pool ?: break
                executor.execute { handle(client) }
            } catch (e: Exception) {
                if (running) {
                    LogUtil.w(TAG, "Accept failed: ${e.message}")
                }
                break
            }
        }
    }

    private fun handle(client: Socket) {
        var upstream: Socket? = null
        try {
            val rt = runtime ?: return
            val input = client.getInputStream()
            val output = client.getOutputStream()

            // --- SOCKS5 greeting ---
            val greeting = readFully(input, 2) ?: return
            if (greeting[0].toInt() != 5) return
            val methodCount = greeting[1].toInt() and 0xff
            readFully(input, methodCount) ?: return
            output.write(byteArrayOf(0x05, 0x00))
            output.flush()

            // --- CONNECT request ---
            val head = readFully(input, 4) ?: return
            if (head[0].toInt() != 5 || head[1].toInt() != 1) {
                writeSocksReply(output, 0x07)
                return
            }
            val atyp = head[3].toInt() and 0xff
            when (atyp) {
                1 -> readFully(input, 4) ?: return
                3 -> {
                    val lenByte = readFully(input, 1) ?: return
                    val len = lenByte[0].toInt() and 0xff
                    readFully(input, len) ?: return
                }
                4 -> readFully(input, 16) ?: return
                else -> return
            }
            val portBytes = readFully(input, 2) ?: return
            val port = ((portBytes[0].toInt() and 0xff) shl 8) or (portBytes[1].toInt() and 0xff)

            val up = Socket()
            openSockets.add(up)
            try {
                up.connect(InetSocketAddress(rt.cacheIp, port), CONNECT_TIMEOUT_MS)
            } catch (e: Exception) {
                openSockets.remove(up)
                writeSocksReply(output, 0x05)
                return
            }
            upstream = up
            up.tcpNoDelay = true
            writeSocksReply(output, 0x00)

            if (port == 80) {
                httpRelay(client, up, rt)
            } else {
                rawRelay(client, up)
            }
        } catch (e: Exception) {
            LogUtil.w(TAG, "Connection ended: ${e.message}")
        } finally {
            openSockets.remove(client)
            closeQuietly(client)
            if (upstream != null) {
                openSockets.remove(upstream)
                closeQuietly(upstream)
            }
        }
    }

    /**
     * client -> upstream: request-aware loop that rewrites every HTTP request
     * header block (keep-alive friendly, same as the official client).
     * upstream -> client: raw byte copy.
     */
    private fun httpRelay(client: Socket, up: Socket, rt: SteamDlManager.Runtime) {
        val responseThread = Thread {
            try {
                copy(up.getInputStream(), client.getOutputStream())
            } catch (_: Exception) {
            } finally {
                closeQuietly(up)
                closeQuietly(client)
            }
        }.apply {
            isDaemon = true
            start()
        }
        try {
            val clientIn = client.getInputStream()
            val upOut: OutputStream = BufferedOutputStream(up.getOutputStream(), 65536)
            while (running && !client.isClosed && !up.isClosed) {
                val block = readHeaderBlock(clientIn) ?: break
                val rewritten = SteamDlHttpRewriter.rewrite(block, rt.cacheDomain, rt.token)
                upOut.write(rewritten)
                upOut.flush()
                val contentLength = SteamDlHttpRewriter.contentLengthOf(block) ?: 0L
                if (contentLength > 0) {
                    copyN(clientIn, upOut, contentLength)
                }
                if (SteamDlHttpRewriter.isChunked(block)) {
                    // Request bodies with chunked encoding stream raw for the rest
                    // of this connection; downloads (GET) never hit this path.
                    copy(clientIn, upOut)
                    break
                }
            }
        } catch (e: Exception) {
            LogUtil.w(TAG, "HTTP relay ended: ${e.message}")
        } finally {
            try {
                up.shutdownOutput()
            } catch (_: Exception) {
            }
            responseThread.join(3000)
        }
    }

    private fun rawRelay(client: Socket, up: Socket) {
        val reverse = Thread {
            try {
                copy(up.getInputStream(), client.getOutputStream())
            } catch (_: Exception) {
            } finally {
                closeQuietly(up)
                closeQuietly(client)
            }
        }.apply {
            isDaemon = true
            start()
        }
        try {
            copy(client.getInputStream(), up.getOutputStream())
        } catch (e: Exception) {
            LogUtil.w(TAG, "TCP relay ended: ${e.message}")
        } finally {
            try {
                up.shutdownOutput()
            } catch (_: Exception) {
            }
            reverse.join(3000)
        }
    }

    private fun copy(inp: InputStream, out: OutputStream) {
        val buffer = ByteArray(65536)
        while (true) {
            val n = inp.read(buffer)
            if (n < 0) return
            if (n > 0) {
                out.write(buffer, 0, n)
                out.flush()
            }
        }
    }

    private fun copyN(inp: InputStream, out: OutputStream, count: Long) {
        val buffer = ByteArray(65536)
        var remaining = count
        while (remaining > 0) {
            val n = inp.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (n < 0) return
            out.write(buffer, 0, n)
            remaining -= n
        }
        out.flush()
    }

    private fun readFully(inp: InputStream, n: Int): ByteArray? {
        if (n <= 0) return ByteArray(0)
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = inp.read(buf, off, n - off)
            if (r < 0) return null
            off += r
        }
        return buf
    }

    /** Reads up to and including the CRLFCRLF terminator; null on EOF or overflow. */
    private fun readHeaderBlock(inp: InputStream): ByteArray? {
        val out = ByteArrayOutputStream(1024)
        var state = 0
        while (out.size() < MAX_HEADER_SIZE) {
            val b = inp.read()
            if (b < 0) return null
            out.write(b)
            state = when (state) {
                0 -> if (b == '\r'.code) 1 else 0
                1 -> if (b == '\n'.code) 2 else if (b == '\r'.code) 1 else 0
                2 -> if (b == '\r'.code) 3 else 0
                3 -> if (b == '\n'.code) 4 else if (b == '\r'.code) 1 else 0
                else -> 4
            }
            if (state == 4) {
                return out.toByteArray()
            }
        }
        return null
    }

    private fun writeSocksReply(output: OutputStream, rep: Int) {
        try {
            output.write(byteArrayOf(0x05, rep.toByte(), 0x00, 0x01, 0, 0, 0, 0, 0, 0))
            output.flush()
        } catch (_: Exception) {
        }
    }

    private fun closeQuietly(socket: Socket?) {
        try {
            socket?.close()
        } catch (_: Exception) {
        }
    }
}

/**
 * Pure HTTP header-block rewriting used by [SteamDlBridge]; kept separate and
 * side-effect free so it can be covered by JVM unit tests.
 *
 * Wire format matches the official SteamDL client:
 *   Host: <cache domain>
 *   Real-Host: <original host>
 *   Auth-Token: <subscription token>
 */
object SteamDlHttpRewriter {

    fun rewrite(headerBlock: ByteArray, cacheDomain: String, token: String): ByteArray {
        val text = String(headerBlock, Charsets.ISO_8859_1)
        val lines = text.split("\r\n").dropLastWhile { it.isEmpty() }
        if (lines.isEmpty()) return headerBlock

        var requestLine = lines[0]
        var originalHost: String? = null

        // absolute-form request target (http://host/path) -> origin-form
        val requestParts = requestLine.split(" ")
        if (requestParts.size == 3 && requestParts[1].startsWith("http://")) {
            val uri = requestParts[1].removePrefix("http://")
            val slash = uri.indexOf('/')
            val authority = if (slash >= 0) uri.substring(0, slash) else uri
            val path = if (slash >= 0) uri.substring(slash) else "/"
            originalHost = authority
            requestLine = "${requestParts[0]} $path ${requestParts[2]}"
        }

        val passthrough = mutableListOf<String>()
        for (i in 1 until lines.size) {
            val line = lines[i]
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val name = line.substring(0, colon).trim()
            val value = line.substring(colon + 1).trim()
            when {
                name.equals("Host", ignoreCase = true) -> originalHost = value
                name.equals("Real-Host", ignoreCase = true) -> Unit // replaced below
                name.equals("Auth-Token", ignoreCase = true) -> Unit // replaced below
                name.equals("Proxy-Connection", ignoreCase = true) -> Unit // drop
                else -> passthrough.add(line)
            }
        }

        val result = StringBuilder()
        result.append(requestLine).append("\r\n")
        result.append("Host: ").append(cacheDomain).append("\r\n")
        if (!originalHost.isNullOrBlank()) {
            result.append("Real-Host: ").append(originalHost).append("\r\n")
        }
        if (token.isNotBlank()) {
            result.append("Auth-Token: ").append(token).append("\r\n")
        }
        passthrough.forEach { result.append(it).append("\r\n") }
        result.append("\r\n")
        return result.toString().toByteArray(Charsets.ISO_8859_1)
    }

    fun contentLengthOf(headerBlock: ByteArray): Long? {
        return headerValue(headerBlock, "Content-Length")?.trim()?.toLongOrNull()
    }

    fun isChunked(headerBlock: ByteArray): Boolean {
        val value = headerValue(headerBlock, "Transfer-Encoding") ?: return false
        return value.contains("chunked", ignoreCase = true)
    }

    private fun headerValue(headerBlock: ByteArray, name: String): String? {
        val text = String(headerBlock, Charsets.ISO_8859_1)
        val lines = text.split("\r\n")
        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            if (line.substring(0, colon).trim().equals(name, ignoreCase = true)) {
                return line.substring(colon + 1)
            }
        }
        return null
    }
}
