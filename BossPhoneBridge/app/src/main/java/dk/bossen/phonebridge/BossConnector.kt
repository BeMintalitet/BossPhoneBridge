package dk.bossen.phonebridge

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONObject

data class DeviceAction(
    val id: String,
    val type: String,
    val payload: JSONObject
)

object BossConnector {
    private const val BASE_URL = "https://boss-device-connector1.onrender.com"

    fun getActions(token: String): List<DeviceAction> {
        val response = request(token, "/v1/device/actions", "GET")
        val actions = response.optJSONArray("actions")
            ?: throw IllegalStateException("Connector response has no actions list")
        return (0 until actions.length()).mapNotNull { index ->
            val action = actions.optJSONObject(index) ?: return@mapNotNull null
            val id = action.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val type = action.optString("type").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            DeviceAction(id, type, action.optJSONObject("payload") ?: JSONObject())
        }
    }

    fun updateAction(token: String, id: String, state: String) {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "Invalid action ID" }
        require(state in setOf("approved", "rejected", "completed")) { "Invalid action state" }
        request(token, "/v1/device/actions/${URLEncoder.encode(id, "UTF-8")}/$state", "POST")
    }

    private fun request(token: String, path: String, method: String): JSONObject {
        val connection = URL("$BASE_URL$path").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Accept", "application/json")
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            if (method == "POST") {
                connection.doOutput = true
                connection.outputStream.use { }
            }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("HTTP $code: $response")
            return if (response.isBlank()) JSONObject() else JSONObject(response)
        } finally {
            connection.disconnect()
        }
    }
}