package com.loyea.mcp

import com.google.gson.JsonElement

data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val id: String? = null,
    val method: String,
    val params: Any? = null
)

data class JsonRpcResponse(
    val jsonrpc: String,
    val id: JsonElement?,
    val result: JsonElement? = null,
    val error: JsonRpcError? = null
) {
    // 重载构造函数以兼容原有 String? ID 传参调用
    constructor(
        jsonrpc: String,
        idStr: String?,
        result: JsonElement? = null,
        error: JsonRpcError? = null
    ) : this(
        jsonrpc,
        idStr?.let { com.google.gson.JsonPrimitive(it) },
        result,
        error
    )

    val idAsString: String?
        get() = when {
            id == null || id.isJsonNull -> null
            id.isJsonPrimitive -> id.asJsonPrimitive.asString
            else -> id.toString()
        }
}

data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null
)
