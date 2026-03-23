package com.zenpeartree.krecords

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.HttpResponseState
import io.hammerhead.karooext.models.OnHttpResponse
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
)

data class HttpResponse(
    val statusCode: Int,
    val headers: Map<String, String>,
    val body: ByteArray?,
    val error: String?,
) {
    fun bodyString(): String = body?.toString(Charsets.UTF_8).orEmpty()
}

interface HttpClient {
    fun request(request: HttpRequest, callback: (HttpResponse) -> Unit)
}

fun HttpClient.requestBlocking(request: HttpRequest, timeoutMs: Long = 45_000L): HttpResponse {
    val latch = CountDownLatch(1)
    var response: HttpResponse? = null
    request(request) {
        response = it
        latch.countDown()
    }
    if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
        return HttpResponse(0, emptyMap(), null, "Timed out")
    }
    return response ?: HttpResponse(0, emptyMap(), null, "No response")
}

class DirectHttpClient : HttpClient {
    private val executor = Executors.newSingleThreadExecutor()

    override fun request(request: HttpRequest, callback: (HttpResponse) -> Unit) {
        executor.execute {
            callback(executeRequest(request))
        }
    }

    private fun executeRequest(request: HttpRequest): HttpResponse {
        return try {
            val connection = (URL(request.url).openConnection() as HttpURLConnection).apply {
                requestMethod = request.method
                connectTimeout = 20_000
                readTimeout = 20_000
                doInput = true
                request.headers.forEach { (key, value) -> setRequestProperty(key, value) }
                if (request.body != null) {
                    doOutput = true
                    outputStream.use { stream: OutputStream ->
                        stream.write(request.body)
                    }
                }
            }

            val status = connection.responseCode
            val body = try {
                val stream = if (status >= 400) connection.errorStream else connection.inputStream
                stream?.readBytes()
            } catch (_: Exception) {
                null
            }
            HttpResponse(
                statusCode = status,
                headers = connection.headerFields.filterKeys { it != null }.mapValues { it.value.joinToString(",") },
                body = body,
                error = null,
            )
        } catch (error: Exception) {
            HttpResponse(0, emptyMap(), null, error.message)
        }
    }
}

class KarooHttpClient(private val karooSystem: KarooSystemService) : HttpClient {
    override fun request(request: HttpRequest, callback: (HttpResponse) -> Unit) {
        if (!karooSystem.connected) {
            callback(HttpResponse(0, emptyMap(), null, "KarooSystem disconnected"))
            return
        }

        var consumerId: String? = null
        consumerId = karooSystem.addConsumer<OnHttpResponse>(
            OnHttpResponse.MakeHttpRequest(
                method = request.method,
                url = request.url,
                headers = request.headers,
                body = request.body,
            ),
            onError = { error ->
                consumerId?.let(karooSystem::removeConsumer)
                callback(HttpResponse(0, emptyMap(), null, error))
            },
        ) { event ->
            val state = event.state
            if (state is HttpResponseState.Complete) {
                consumerId?.let(karooSystem::removeConsumer)
                callback(
                    HttpResponse(
                        statusCode = state.statusCode,
                        headers = state.headers,
                        body = state.body,
                        error = state.error,
                    )
                )
            }
        }
    }
}
