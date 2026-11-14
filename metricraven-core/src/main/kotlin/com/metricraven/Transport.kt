package com.metricraven

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/** The HTTP answer the client needs from one collect request. */
public class TransportResponse @JvmOverloads constructor(
    public val status: Int,
    /** Seconds from a Retry-After header, when the server sent one. */
    public val retryAfter: Double? = null,
    public val body: ByteArray = ByteArray(0),
)

/**
 * Sends one JSON body to the collect endpoint. Throws an [IOException] when the network is
 * unreachable. Called on the client's own background thread, never the main thread.
 */
public fun interface MetricRavenTransport {
    @Throws(IOException::class)
    public fun post(body: ByteArray, url: String): TransportResponse
}

/** HttpURLConnection, with 15 second timeouts. No dependencies. */
public class HttpUrlConnectionTransport @JvmOverloads constructor(
    private val timeoutMillis: Int = 15_000,
) : MetricRavenTransport {
    override fun post(body: ByteArray, url: String): TransportResponse {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = timeoutMillis
            connection.readTimeout = timeoutMillis
            connection.doOutput = true
            connection.useCaches = false
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            val retryAfter = connection.getHeaderField("Retry-After")?.trim()?.toDoubleOrNull()
            val stream: InputStream? = if (status in 200..299) connection.inputStream else connection.errorStream
            val data = stream?.use { it.readBytes() } ?: ByteArray(0)
            return TransportResponse(status, retryAfter, data)
        } finally {
            connection.disconnect()
        }
    }
}

/** Persists the offline queue and, with consent, the visitor ID. */
public interface MetricRavenStorage {
    public fun getString(key: String): String?
    public fun setString(key: String, value: String?)
}

/** Keeps everything in memory: the queue is lost when the process ends. Used by tests and the JVM default. */
public class MemoryStorage : MetricRavenStorage {
    private val values = ConcurrentHashMap<String, String>()
    override fun getString(key: String): String? = values[key]
    override fun setString(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}
