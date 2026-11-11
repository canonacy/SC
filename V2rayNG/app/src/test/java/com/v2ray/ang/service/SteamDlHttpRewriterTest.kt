package com.v2ray.ang.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SteamDlHttpRewriterTest {

    private fun block(text: String): ByteArray = text.toByteArray(Charsets.ISO_8859_1)

    private fun asText(bytes: ByteArray): String = String(bytes, Charsets.ISO_8859_1)

    @Test
    fun rewriteReplacesHostAddsRealHostAndToken() {
        val input = block(
            "GET /file HTTP/1.1\r\n" +
                "Host: cdn.example.com\r\n" +
                "User-Agent: Launcher\r\n" +
                "\r\n",
        )
        val out = asText(SteamDlHttpRewriter.rewrite(input, "dl.steamdl.ir", "tok123"))

        assertTrue(out.startsWith("GET /file HTTP/1.1\r\n"))
        assertTrue(out.contains("Host: dl.steamdl.ir\r\n"))
        assertTrue(out.contains("Real-Host: cdn.example.com\r\n"))
        assertTrue(out.contains("Auth-Token: tok123\r\n"))
        assertTrue(out.contains("User-Agent: Launcher\r\n"))
        assertTrue(out.endsWith("\r\n\r\n"))
        assertFalse(out.contains("\r\nHost: cdn.example.com"))
    }

    @Test
    fun rewriteOmitsTokenWhenBlank() {
        val input = block("GET / HTTP/1.1\r\nHost: a.example\r\n\r\n")
        val out = asText(SteamDlHttpRewriter.rewrite(input, "dl.steamdl.ir", ""))

        assertFalse(out.contains("Auth-Token"))
        assertTrue(out.contains("Real-Host: a.example\r\n"))
    }

    @Test
    fun rewriteDropsProxyConnectionHeader() {
        val input = block("GET / HTTP/1.1\r\nHost: a.example\r\nProxy-Connection: keep-alive\r\n\r\n")
        val out = asText(SteamDlHttpRewriter.rewrite(input, "dl.steamdl.ir", "t"))

        assertFalse(out.contains("Proxy-Connection"))
    }

    @Test
    fun rewriteConvertsAbsoluteFormRequestTarget() {
        val input = block(
            "GET http://fastly.example/path/to/file.bin HTTP/1.1\r\n" +
                "Host: fastly.example\r\n" +
                "\r\n",
        )
        val out = asText(SteamDlHttpRewriter.rewrite(input, "dl.steamdl.ir", ""))

        assertTrue(out.startsWith("GET /path/to/file.bin HTTP/1.1\r\n"))
        assertTrue(out.contains("Real-Host: fastly.example\r\n"))
    }

    @Test
    fun rewriteKeepsExistingRealHostConsistent() {
        val input = block("GET / HTTP/1.1\r\nHost: b.example\r\n\r\n")
        val out = asText(SteamDlHttpRewriter.rewrite(input, "dl.steamdl.ir", ""))

        assertEquals(1, Regex("Real-Host:").findAll(out).count())
        assertEquals(1, Regex("\r\nHost:").findAll(out).count())
    }

    @Test
    fun contentLengthIsParsedCaseInsensitively() {
        val input = block("POST / HTTP/1.1\r\nHost: a\r\ncontent-length: 1234\r\n\r\n")
        assertEquals(1234L, SteamDlHttpRewriter.contentLengthOf(input))
        assertNull(SteamDlHttpRewriter.contentLengthOf(block("GET / HTTP/1.1\r\nHost: a\r\n\r\n")))
    }

    @Test
    fun chunkedTransferEncodingDetected() {
        val input = block("POST / HTTP/1.1\r\nHost: a\r\nTransfer-Encoding: chunked\r\n\r\n")
        assertTrue(SteamDlHttpRewriter.isChunked(input))
        assertFalse(SteamDlHttpRewriter.isChunked(block("GET / HTTP/1.1\r\nHost: a\r\n\r\n")))
    }
}
