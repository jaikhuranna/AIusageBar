package com.jaikhurana.aiusagewear

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** The AIusageBar bridge's HTTP API. Blocking; call off the main thread. */
object Bridge {
    sealed interface Usage {
        data class Fresh(val body: String, val etag: String?) : Usage
        data object NotModified : Usage
        data object Unauthorized : Usage
        data class Failed(val reason: String) : Usage
    }

    data class Paired(val token: String, val publicUrl: String?, val host: String)

    fun usage(base: String, token: String, etag: String?): Usage = try {
        val c = open("$base/usage", token)
        etag?.let { c.setRequestProperty("If-None-Match", it) }
        try {
            when (c.responseCode) {
                200 -> Usage.Fresh(c.inputStream.bufferedReader().use { it.readText() }, c.getHeaderField("ETag"))
                304 -> Usage.NotModified
                401 -> Usage.Unauthorized
                else -> Usage.Failed("HTTP ${c.responseCode}")
            }
        } finally {
            c.disconnect()
        }
    } catch (e: IOException) {
        Usage.Failed(e.message ?: "network error")
    }

    /** The raw events page, or null if it couldn't be fetched this time. */
    fun events(base: String, token: String, since: String?): String? = try {
        val c = open("$base/events" + (since?.let { "?since=$it" } ?: ""), token)
        try {
            if (c.responseCode == 200) c.inputStream.bufferedReader().use { it.readText() } else null
        } finally {
            c.disconnect()
        }
    } catch (e: IOException) {
        null
    }

    fun pair(base: String, code: String, name: String): Result<Paired> = try {
        val c = open("$base/pair", token = null)
        c.requestMethod = "POST"
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use {
            it.write(JSONObject().put("code", code).put("name", name).toString().toByteArray())
        }
        try {
            when (c.responseCode) {
                200 -> {
                    val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                    Result.success(
                        Paired(
                            token = o.getString("token"),
                            publicUrl = o.optString("public_url").takeIf { it.startsWith("https://") },
                            host = o.optString("host"),
                        ),
                    )
                }
                403 -> Result.failure(IOException("Wrong or expired code. Get a new one."))
                else -> Result.failure(IOException("The bridge said HTTP ${c.responseCode}"))
            }
        } finally {
            c.disconnect()
        }
    } catch (e: IOException) {
        Result.failure(IOException("Can't reach the bridge at $base"))
    }

    private fun open(url: String, token: String?): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            // Generous: a Bluetooth proxy through the phone can take a while.
            connectTimeout = 20_000
            readTimeout = 20_000
            token?.let { setRequestProperty("Authorization", "Bearer $it") }
        }
}
