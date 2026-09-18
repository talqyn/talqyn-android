package com.talqyn.sdk

import java.net.URI
import java.net.URISyntaxException
import kotlin.time.Duration

/**
 * Builds URLs and [TalqynHttpRequest]s.
 *
 * A separate type because the path is significant: instant search carries a
 * trailing slash (`/v1/search/`) and the rest do not, and joining must not lose it.
 */
internal class TalqynRequestBuilder(
    private val baseUrl: String,
    private val apiVersion: String,
    private val timeout: Duration,
) {
    /**
     * Joins the base, the version, and [path] into a URL.
     *
     * [path] is a `/`-separated route whose segments are escaped one by one; values
     * that may themselves contain a `/` go through [segment] first and pass through
     * untouched. A query on the base URL — a gateway key, say — is kept in front of
     * the request's own items.
     */
    fun url(path: String, query: List<Pair<String, String>> = emptyList()): String {
        val base = try {
            URI(baseUrl)
        } catch (e: URISyntaxException) {
            throw TalqynException.InvalidConfiguration("could not parse baseUrl: $baseUrl")
        }
        val scheme = base.scheme
        val authority = base.rawAuthority
        if (scheme == null || authority == null) {
            throw TalqynException.InvalidConfiguration("could not parse baseUrl: $baseUrl")
        }
        val prefix = (base.rawPath ?: "").trimEnd('/')
        val version = encode(apiVersion.trim('/'), keepPercent = true)
        val route = path.trimStart('/').split('/').joinToString("/") { encode(it, keepPercent = true) }
        val items = listOfNotNull(base.rawQuery?.takeIf { it.isNotEmpty() }) +
            query.map { (name, value) -> "${encodeQuery(name)}=${encodeQuery(value)}" }

        return buildString {
            append(scheme).append("://").append(authority).append(prefix)
            if (version.isNotEmpty()) append('/').append(version)
            append('/').append(route)
            if (items.isNotEmpty()) append('?').append(items.joinToString("&"))
        }
    }

    fun request(
        method: String,
        path: String,
        query: List<Pair<String, String>> = emptyList(),
        body: ByteArray? = null,
        headers: Map<String, String> = emptyMap(),
        accept: String = "application/json",
        timeout: Duration? = null,
    ): TalqynHttpRequest {
        val all = LinkedHashMap<String, String>()
        fun set(field: String, value: String) {
            all.keys.firstOrNull { it.equals(field, ignoreCase = true) }?.let(all::remove)
            all[field] = value
        }
        set("Accept", accept)
        // Set before the caller's own headers, which may override it.
        set("X-Talqyn-SDK", Talqyn.CLIENT_HEADER)
        if (body != null) set("Content-Type", "application/json")
        for ((field, value) in headers) set(field, value)
        return TalqynHttpRequest(method, url(path, query), all, body, timeout ?: this.timeout)
    }

    companion object {
        private const val HEX = "0123456789ABCDEF"

        /** What may stand unescaped inside one path segment besides letters and digits. */
        private const val PATH_EXTRA = "-._~!\$&'()*+,=:@"

        /** What may stand unescaped in a query name or value besides letters and digits. */
        private const val QUERY_EXTRA = "-._~"

        /**
         * Escapes a value for use as one segment of a path.
         *
         * A `/` inside it becomes `%2F` rather than a separator: a session id is a
         * value, not a route, and `chats/../../admin` must reach the server as exactly
         * that string under `chats/`.
         */
        fun segment(raw: String): String = encode(raw, keepPercent = false)

        /**
         * Percent-encodes everything outside the path charset. With [keepPercent], a
         * `%` stays, so a path assembled from segments already escaped with [segment]
         * is not escaped a second time.
         */
        private fun encode(raw: String, keepPercent: Boolean): String =
            percentEncode(raw) { char -> char in PATH_EXTRA || (keepPercent && char == '%') }

        private fun encodeQuery(raw: String): String = percentEncode(raw) { char -> char in QUERY_EXTRA }

        private inline fun percentEncode(raw: String, allowed: (Char) -> Boolean): String {
            val out = StringBuilder(raw.length)
            for (byte in raw.toByteArray(Charsets.UTF_8)) {
                val code = byte.toInt() and 0xFF
                val char = code.toChar()
                val isAsciiAlphanumeric = char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9'
                if (code < 0x80 && (isAsciiAlphanumeric || allowed(char))) {
                    out.append(char)
                } else {
                    out.append('%').append(HEX[code shr 4]).append(HEX[code and 0xF])
                }
            }
            return out.toString()
        }
    }
}
