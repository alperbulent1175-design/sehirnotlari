package com.sehirnotlari.cloud

import org.json.JSONObject

/** `window.onCloudResult(op, requestId, result, error)`: the only way cloud results reach the page. */
object CloudScripts {
    val OPS = setOf("signIn", "signOut", "backup", "list", "fetch")
    val REQUEST_ID = Regex("""[A-Za-z0-9-]{1,100}""")

    fun result(op: String, requestId: String, result: JSONObject?, error: String?): String {
        require(op in OPS && REQUEST_ID.matches(requestId))
        val body = listOf(JSONObject.quote(op), JSONObject.quote(requestId), result?.toString() ?: "null", error?.let { JSONObject.quote(it) } ?: "null")
            .joinToString(",")
            .replace("\u2028", "\\u2028")
            .replace("\u2029", "\\u2029")
        return "window.onCloudResult && window.onCloudResult($body);"
    }
}
