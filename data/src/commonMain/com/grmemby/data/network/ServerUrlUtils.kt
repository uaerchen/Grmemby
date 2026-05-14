package com.grmemby.data.network

fun trimTrailingSlash(url: String, trailingSlash: Boolean = false): String {
    var normalized = url
    while (normalized.endsWith("/")) {
        normalized = normalized.dropLast(1)
    }
    return if (trailingSlash) "$normalized/" else normalized
}

fun canonicalServerUrl(url: String): String {
    return trimTrailingSlash(url.trim())
}

fun canonicalServerUrlKey(url: String): String {
    return serverUrlKeyWithoutDefaultPort(canonicalServerUrl(url))
}

fun sameServerUrl(left: String?, right: String?): Boolean {
    if (left.isNullOrBlank() || right.isNullOrBlank()) return false
    return canonicalServerUrlKey(left) == canonicalServerUrlKey(right)
}

fun canonicalServerUrlWithDefaultPort(url: String): String {
    val normalized = canonicalServerUrl(url)
    return applyDefaultPort(normalized, explicitDefaultPort = true)
}

fun buildBaseUrlCandidates(serverUrl: String): List<String> {
    val normalized = trimTrailingSlash(serverUrl.trim())
    if (normalized.endsWith("/emby", ignoreCase = true)) {
        return listOf(normalized)
    }

    return listOf(normalized, "$normalized/emby")
}

private fun serverUrlKeyWithoutDefaultPort(url: String): String {
    return applyDefaultPort(url, explicitDefaultPort = false).lowercase()
}

private fun applyDefaultPort(url: String, explicitDefaultPort: Boolean): String {
    val schemeEnd = url.indexOf("://")
    if (schemeEnd <= 0) return url

    val scheme = url.substring(0, schemeEnd).lowercase()
    val authorityStart = schemeEnd + 3
    val pathStart = url.indexOf('/', authorityStart).let { if (it == -1) url.length else it }
    val authority = url.substring(authorityStart, pathStart)
    val pathAndQuery = url.substring(pathStart)
    if (authority.isBlank() || authority.contains('@')) return url

    val closeIpv6 = authority.indexOf(']')
    val hasExplicitPort = if (authority.startsWith("[")) {
        closeIpv6 != -1 && closeIpv6 + 1 < authority.length && authority[closeIpv6 + 1] == ':'
    } else {
        authority.lastIndexOf(':') > -1
    }
    val defaultPort = when (scheme) {
        "https" -> 443
        "http" -> 80
        else -> return url
    }

    val normalizedAuthority = if (hasExplicitPort) {
        val portSeparator = if (authority.startsWith("[")) closeIpv6 + 1 else authority.lastIndexOf(':')
        val host = authority.substring(0, portSeparator)
        val port = authority.substring(portSeparator + 1)
        if (!explicitDefaultPort && port.toIntOrNull() == defaultPort) host else authority
    } else if (explicitDefaultPort) {
        "$authority:$defaultPort"
    } else {
        authority
    }

    return url.substring(0, authorityStart) + normalizedAuthority + pathAndQuery
}
