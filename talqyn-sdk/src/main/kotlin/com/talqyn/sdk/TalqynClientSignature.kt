package com.talqyn.sdk

import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Signs a device-token mint request with the storefront's client key.
 *
 * `POST /v1/consultant/token` is guarded by this signature rather than by a tenant
 * key, because there is no tenant key on a device to guard it with. The secret
 * itself never travels: a request carries the key id, a timestamp, a nonce, and an
 * HMAC over the exact bytes of the body.
 *
 * ```
 * signing_input = "talqyn-device-mint-v1" + "\n"
 *               + key_id + "\n"
 *               + timestamp + "\n"
 *               + nonce + "\n"
 *               + hex(SHA256(body_bytes))
 * X-Client-Sig  = hex(HMAC-SHA256(secret, signing_input))
 * ```
 *
 * `body_bytes` must be **exactly** the bytes that go on the wire: serialize the
 * body once and sign what you send. Re-serializing can reorder keys or change
 * whitespace, and the signature will not match.
 *
 * [Talqyn] performs this for you; the object is public so a storefront that mints
 * tokens through its own transport can reuse the same algorithm.
 */
public object TalqynClientSignature {
    /**
     * The context label baked into every signing input. Changing it invalidates
     * every shipped build at once, which is why the version lives inside the string.
     */
    public const val CONTEXT: String = "talqyn-device-mint-v1"

    /**
     * The acceptance window: the server accepts a timestamp within ±300 s of its own
     * clock. A timestamp outside it means a badly skewed clock or a replayed request.
     */
    public val maxSkew: Duration = 300.seconds

    private val random = SecureRandom()

    /**
     * Builds the four signature headers for a mint request body.
     *
     * @param keyId The client key id.
     * @param secret The client key secret. Used to sign; never sent.
     * @param body The exact request body bytes that will be transmitted.
     * @param timestamp The moment to stamp the request with. Defaults to now.
     * @param nonce The single-use request nonce. Defaults to a fresh random value.
     * @return `X-Client-Key`, `X-Client-Timestamp`, `X-Client-Nonce`, and `X-Client-Sig`.
     */
    @JvmStatic
    @JvmOverloads
    public fun headers(
        keyId: String,
        secret: String,
        body: ByteArray,
        timestamp: Instant = Instant.now(),
        nonce: String = makeNonce(),
    ): Map<String, String> {
        val stamp = timestamp.epochSecond.toString()
        return linkedMapOf(
            "X-Client-Key" to keyId,
            "X-Client-Timestamp" to stamp,
            "X-Client-Nonce" to nonce,
            "X-Client-Sig" to sign(secret = secret, keyId = keyId, timestamp = stamp, nonce = nonce, body = body),
        )
    }

    /**
     * Computes the `X-Client-Sig` value for a mint request.
     *
     * @param timestamp Unix time in seconds, as a decimal string.
     * @return `hex(HMAC-SHA256(secret, signingInput))`, 64 lowercase hexadecimal characters.
     */
    @JvmStatic
    public fun sign(secret: String, keyId: String, timestamp: String, nonce: String, body: ByteArray): String {
        // HMAC pads a short key with zero bytes, so an empty key and a single zero
        // byte are the same key — and `SecretKeySpec` refuses the empty one.
        val key = secret.toByteArray(Charsets.UTF_8).takeIf { it.isNotEmpty() } ?: byteArrayOf(0)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return hex(mac.doFinal(signingInput(keyId = keyId, timestamp = timestamp, nonce = nonce, body = body)))
    }

    /**
     * Assembles the bytes that get signed: the five newline-separated fields.
     *
     * The body enters as a digest rather than verbatim: the signature has to cover
     * the exact bytes sent, without depending on how they would be encoded inside a
     * header.
     */
    @JvmStatic
    public fun signingInput(keyId: String, timestamp: String, nonce: String, body: ByteArray): ByteArray {
        val digest = hex(MessageDigest.getInstance("SHA-256").digest(body))
        return listOf(CONTEXT, keyId, timestamp, nonce, digest).joinToString("\n").toByteArray(Charsets.UTF_8)
    }

    /**
     * Generates a single-use request nonce: 24 hexadecimal characters, within the
     * contract's 16–64 character `[A-Za-z0-9_-]` range. The server remembers a nonce
     * for the length of the acceptance window and rejects a repeat, so a fresh value
     * is required for **every** mint request.
     */
    @JvmStatic
    public fun makeNonce(): String {
        val bytes = ByteArray(12)
        random.nextBytes(bytes)
        return hex(bytes)
    }

    private fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            append("0123456789abcdef"[value shr 4])
            append("0123456789abcdef"[value and 0xF])
        }
    }
}
