package org.darkan.core.net.login

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Minimal HMAC-signed world login token.
 *
 * This is intentionally small (JWT-like) without external dependencies.
 *
 * Format:
 *   base64url(payload) + "." + base64url(hmacSha256(payload))
 *
 * Payload formats:
 *
 * - v1 (legacy, verbose, UTF-8): "v1|username|displayName|rights|issuedAtMs|expiresAtMs"
 *   NOTE: This can be too large to fit inside the 1024-bit RSA block used for world login.
 *
 * - v2 (compact, binary):
 *   [0]   version = 2
 *   [1..4]  issuedAtSec (uint32, big-endian)
 *   [5..8]  expiresAtSec (uint32, big-endian)
 *   [9]   usernameLen (uint8)
 *   [10..] username bytes (utf-8)
 *   [..]  nonce (8 bytes)
 *
 * Signature is HMAC-SHA256(payload) truncated to 16 bytes.
 */
object WorldLoginToken {

    data class VerifiedWorldLoginToken(
        val username: String,
        val issuedAtMs: Long,
        val expiresAtMs: Long,
        val version: Int,
    )

    fun issue(
        username: String,
        displayName: String,
        rights: Int,
        nowMs: Long,
        ttlMs: Long,
        secret: String,
    ): String {
        // v2 compact token: keep it safely under the 1024-bit RSA plaintext size used by the client.
        val issuedAtSec = (nowMs / 1000L).toInt()
        val expiresAtSec = ((nowMs + ttlMs) / 1000L).toInt()
        val usernameBytes = username.toByteArray(StandardCharsets.UTF_8)
        require(usernameBytes.size in 1..255) { "username too long for world login token" }

        val payload = ByteArray(1 + 4 + 4 + 1 + usernameBytes.size + 8)
        var i = 0
        payload[i++] = 2 // version
        writeU32(payload, i, issuedAtSec); i += 4
        writeU32(payload, i, expiresAtSec); i += 4
        payload[i++] = usernameBytes.size.toByte()
        usernameBytes.copyInto(payload, destinationOffset = i)
        i += usernameBytes.size
        RNG.nextBytes(payload, i, 8)

        val sig = hmacSha256(payload, secret).copyOfRange(0, 16)
        return "${b64Url(payload)}.${b64Url(sig)}"
    }

    fun verify(token: String, nowMs: Long, secret: String): VerifiedWorldLoginToken? {
        val dot = token.indexOf('.')
        if (dot <= 0 || dot == token.lastIndex) return null

        val payloadPart = token.substring(0, dot)
        val sigPart = token.substring(dot + 1)

        val payloadBytes = try {
            b64UrlDecode(payloadPart)
        } catch (_: IllegalArgumentException) {
            return null
        }
        val sigBytes = try {
            b64UrlDecode(sigPart)
        } catch (_: IllegalArgumentException) {
            return null
        }

        // v2: verify truncated signature (16 bytes)
        if (payloadBytes.isNotEmpty() && payloadBytes[0] == 2.toByte()) {
            val expected = hmacSha256(payloadBytes, secret).copyOfRange(0, 16)
            if (!MessageDigest.isEqual(expected, sigBytes)) return null
            if (payloadBytes.size < 1 + 4 + 4 + 1 + 1 + 8) return null

            var i = 1
            val issuedAtSec = readU32(payloadBytes, i); i += 4
            val expiresAtSec = readU32(payloadBytes, i); i += 4
            val usernameLen = payloadBytes[i++].toInt() and 0xff
            if (usernameLen <= 0) return null
            if (payloadBytes.size < 1 + 4 + 4 + 1 + usernameLen + 8) return null
            val username = payloadBytes.copyOfRange(i, i + usernameLen).toString(StandardCharsets.UTF_8)

            val issuedAtMs = issuedAtSec.toLong() * 1000L
            val expiresAtMs = expiresAtSec.toLong() * 1000L
            if (expiresAtMs < nowMs) return null

            return VerifiedWorldLoginToken(
                username = username,
                issuedAtMs = issuedAtMs,
                expiresAtMs = expiresAtMs,
                version = 2,
            )
        }

        // v1 legacy fallback (verbose)
        val expected = hmacSha256(payloadBytes, secret)
        if (!MessageDigest.isEqual(expected, sigBytes)) return null

        val payload = payloadBytes.toString(StandardCharsets.UTF_8)
        val parts = payload.split('|')
        if (parts.size != 6) return null
        if (parts[0] != "v1") return null

        val username = parts[1]
        val issuedAt = parts[4].toLongOrNull() ?: return null
        val expiresAt = parts[5].toLongOrNull() ?: return null
        if (expiresAt < nowMs) return null

        return VerifiedWorldLoginToken(
            username = username,
            issuedAtMs = issuedAt,
            expiresAtMs = expiresAt,
            version = 1,
        )
    }

    private fun hmacSha256(payload: ByteArray, secret: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(payload)
    }

    private fun b64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun b64UrlDecode(s: String): ByteArray =
        Base64.getUrlDecoder().decode(s)

    private fun writeU32(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }

    private fun readU32(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)
    }

    private object RNG {
        private val random = SecureRandom()
        fun nextBytes(dst: ByteArray, offset: Int, len: Int) {
            val tmp = ByteArray(len)
            random.nextBytes(tmp)
            tmp.copyInto(dst, destinationOffset = offset)
        }
    }
}


