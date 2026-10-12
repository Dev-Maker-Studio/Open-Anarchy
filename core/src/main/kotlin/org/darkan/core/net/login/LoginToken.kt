package org.darkan.core.net.login

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object LoginToken {
    private const val VERSION: Byte = 1
    private const val NONCE_BYTES = 8
    private const val SIGNATURE_BYTES = 16
    private const val HEADER_BYTES = 1 + 4 + 4 + 1

    private val secureRandom = SecureRandom()
    private val base64Encoder = Base64.getUrlEncoder().withoutPadding()
    private val base64Decoder = Base64.getUrlDecoder()

    data class Verified(
        val username: String,
        val issuedAtMs: Long,
        val expiresAtMs: Long,
    )

    fun issue(username: String, nowMs: Long, ttlMs: Long, secret: String, ): String {
        val usernameBytes = username.toByteArray(StandardCharsets.UTF_8)
        require(usernameBytes.size in 1..255) { "Username must be 1-255 UTF-8 bytes" }

        val payload = ByteBuffer.allocate(HEADER_BYTES + usernameBytes.size + NONCE_BYTES).apply {
            put(VERSION)
            putInt((nowMs / 1000).toInt())
            putInt(((nowMs + ttlMs) / 1000).toInt())
            put(usernameBytes.size.toByte())
            put(usernameBytes)
            put(generateNonce())
        }.array()

        val signature = computeHmac(payload, secret).copyOf(SIGNATURE_BYTES)
        return "${base64Encoder.encodeToString(payload)}.${base64Encoder.encodeToString(signature)}"
    }

    fun hasTokenShape(candidate: String): Boolean = parse(candidate) != null

    fun verify(token: String, nowMs: Long, secret: String): Verified? {
        val parsed = parse(token) ?: return null

        val expected = computeHmac(parsed.payload, secret).copyOf(SIGNATURE_BYTES)
        if (!MessageDigest.isEqual(expected, parsed.signature)) return null

        if (parsed.expiresAtMs < nowMs) return null

        return Verified(parsed.username, parsed.issuedAtMs, parsed.expiresAtMs)
    }

    private class Parsed(
        val payload: ByteArray,
        val signature: ByteArray,
        val username: String,
        val issuedAtMs: Long,
        val expiresAtMs: Long,
    )

    private fun parse(token: String): Parsed? {
        val dotIndex = token.indexOf('.')
        if (dotIndex <= 0 || dotIndex >= token.lastIndex) return null
        if (token.indexOf('.', dotIndex + 1) != -1) return null

        val payload = runCatching { base64Decoder.decode(token.substring(0, dotIndex)) }.getOrNull() ?: return null
        val signature = runCatching { base64Decoder.decode(token.substring(dotIndex + 1)) }.getOrNull() ?: return null

        if (payload.isEmpty() || payload[0] != VERSION) return null

        val minLength = HEADER_BYTES + 1 + NONCE_BYTES
        if (payload.size < minLength || signature.size != SIGNATURE_BYTES) return null

        val buffer = ByteBuffer.wrap(payload)
        buffer.get()

        val issuedAtSec = buffer.int.toLong() and 0xFFFFFFFFL
        val expiresAtSec = buffer.int.toLong() and 0xFFFFFFFFL
        val usernameLen = buffer.get().toInt() and 0xFF

        if (usernameLen < 1 || buffer.remaining() < usernameLen + NONCE_BYTES) return null

        val usernameBytes = ByteArray(usernameLen)
        buffer.get(usernameBytes)

        return Parsed(
            payload = payload,
            signature = signature,
            username = usernameBytes.toString(StandardCharsets.UTF_8),
            issuedAtMs = issuedAtSec * 1000,
            expiresAtMs = expiresAtSec * 1000,
        )
    }

    private fun computeHmac(data: ByteArray, secret: String): ByteArray {
        val keyBytes = secret.toByteArray(StandardCharsets.UTF_8)
        return Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(keyBytes, "HmacSHA256"))
        }.doFinal(data)
    }

    private fun generateNonce(): ByteArray = ByteArray(NONCE_BYTES).also { secureRandom.nextBytes(it) }
}