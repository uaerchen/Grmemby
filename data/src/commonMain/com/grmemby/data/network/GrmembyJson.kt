package com.grmemby.data.network

import kotlinx.serialization.json.Json

val GrmembyJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    isLenient = true
}
