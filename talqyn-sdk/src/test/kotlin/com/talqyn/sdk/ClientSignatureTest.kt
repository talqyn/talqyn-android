package com.talqyn.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reference values come from the server's own algorithm
 * (`app/services/cip/device_token.sign_mint_request`): signing has to match it byte for byte, or
 * minting answers 401.
 */
class ClientSignatureTest {
    private val keyId = "ck_3f9a1c2b7d4e"
    private val secret = "s3cr3t-client-key-value-32-chars-long"
    private val timestamp = "1756208100"
    private val nonce = "8c1d0b6e2f4a9b3c7d5e1f0a"

    @Test
    fun signatureMatchesServerReference() {
        val body = """{"storefront":"myshop","user_id":"6f1c2b9a-3e47-4b8f-9a10-2c5d8e7f4a01"}""".toByteArray()
        val signature = TalqynClientSignature.sign(secret = secret, keyId = keyId, timestamp = timestamp, nonce = nonce, body = body)
        assertEquals("ec3c1408b51f7abe58002c92db2d8d45411312188f3b81da618f83607140e92b", signature)
    }

    @Test
    fun guestBodySignatureMatchesServerReference() {
        val body = """{"storefront":"myshop"}""".toByteArray()
        val signature = TalqynClientSignature.sign(secret = secret, keyId = keyId, timestamp = timestamp, nonce = nonce, body = body)
        assertEquals("cb146efcb273072c067e6753eb782d317004c9657c1e5e7d3fbe6a7515973afe", signature)
    }

    /** The body the minter writes is exactly the one the server reference was computed over. */
    @Test
    fun minterBodyBytesMatchTheReferenceBody() {
        val body = TalqynJson.encode(
            jsonObject {
                put("storefront", "myshop")
                putIfNotNull("user_id", "6f1c2b9a-3e47-4b8f-9a10-2c5d8e7f4a01")
            },
        )
        assertEquals("""{"storefront":"myshop","user_id":"6f1c2b9a-3e47-4b8f-9a10-2c5d8e7f4a01"}""", body)
    }

    @Test
    fun signingInputShape() {
        val input = TalqynClientSignature.signingInput(
            keyId = keyId,
            timestamp = timestamp,
            nonce = nonce,
            body = """{"storefront":"myshop"}""".toByteArray(),
        )
        val lines = input.toString(Charsets.UTF_8).split("\n")
        assertEquals(5, lines.size)
        assertEquals("talqyn-device-mint-v1", lines[0])
        assertEquals(keyId, lines[1])
        assertEquals(timestamp, lines[2])
        assertEquals(nonce, lines[3])
        assertEquals(64, lines[4].length)
    }

    @Test
    fun headersCarryEverythingServerRequires() {
        val headers = TalqynClientSignature.headers(keyId = keyId, secret = secret, body = "{}".toByteArray())
        assertEquals(keyId, headers["X-Client-Key"])
        assertNotNull(headers["X-Client-Timestamp"])
        assertEquals(64, headers["X-Client-Sig"]?.length)

        val nonce = headers["X-Client-Nonce"].orEmpty()
        // Server contract: 16-64 characters of [A-Za-z0-9_-].
        assertTrue("nonce length out of range: ${nonce.length}", nonce.length in 16..64)
        assertTrue(Regex("^[A-Za-z0-9_-]+$").matches(nonce))
    }

    @Test
    fun nonceIsFreshEveryTime() {
        val nonces = (0 until 200).map { TalqynClientSignature.makeNonce() }.toSet()
        assertEquals("nonce repeated — the server would reject the request as replayed", 200, nonces.size)
    }

    /** HMAC pads a short key with zeros, so an empty secret still signs rather than throwing. */
    @Test
    fun emptySecretStillSigns() {
        val signature = TalqynClientSignature.sign(secret = "", keyId = keyId, timestamp = timestamp, nonce = nonce, body = ByteArray(0))
        assertEquals(64, signature.length)
    }
}
