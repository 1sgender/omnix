package com.omnix.server.ratelimit

import java.net.InetAddress

/**
 * Key for per-source rate limiting.
 *
 * An IPv4 address identifies a source on its own. A single IPv6 subscriber, on the
 * other hand, normally controls a whole /64 and can switch addresses inside it at
 * will, so keying a limiter by the full IPv6 address would hand every new address a
 * fresh budget. IPv6 sources are therefore reduced to their /64 prefix.
 *
 * The input is the canonical literal produced by the trusted-proxy layer
 * (`ProxyRequestSecurity`); anything that cannot be parsed as an address literal is
 * kept verbatim so it can never widen a budget.
 */
object SourceKey {
    private const val UNKNOWN = "unknown"
    private const val IPV6_BYTES = 16
    private const val IPV6_PREFIX_BYTES = 8

    fun of(address: String?): String {
        val value = address?.trim()?.substringBefore('%').orEmpty()
        if (value.isEmpty()) return UNKNOWN
        if (':' !in value) return value
        val bytes = runCatching { InetAddress.getByName(value).address }.getOrNull() ?: return value
        if (bytes.size != IPV6_BYTES) return value
        return bytes.take(IPV6_PREFIX_BYTES)
            .chunked(2)
            .joinToString(":") { pair -> "%02x%02x".format(pair[0], pair[1]) } + "::/64"
    }
}
