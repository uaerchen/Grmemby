package com.grmemby.data.network

import com.grmemby.data.model.ServerInfo

data class ServerEndpoint(
    val baseUrl: String,
    val serverType: ServerType,
    val serverInfo: ServerInfo
)
