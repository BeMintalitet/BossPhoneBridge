package dk.bossen.phonebridge

import java.net.HttpURLConnection
import java.net.URL

object BossConnector {
    private const val BASE = "https://boss-device-connector1.onrender.com"

    fun getActions(token: String): String {
        val connection = URL("$BASE/v1/device/actions").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.connectTimeout = 15000
            connection.readTimeout = 15000

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) throw Exception("HTTP $code: $response")
            return response
        } finally {
            connection.disconnect()
        }
    }

    fun updateAction(token: String, id: String, state: String) {
        require(state in setOf("approved", "rejected", "completed")) { "Invalid action state" }
        val connection = URL("$BASE/v1/device/actions/$id/$state").openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            if (connection.responseCode !in 200..299) {
                throw Exception("HTTP ${connection.responseCode}")
            }
        } finally {
            connection.disconnect()
        }
    }
}