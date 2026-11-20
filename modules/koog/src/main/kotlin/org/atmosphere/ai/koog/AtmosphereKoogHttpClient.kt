/*
 * Copyright 2008-2026 Async-IO.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.atmosphere.ai.koog

import ai.koog.http.client.KoogHttpClient
import ai.koog.http.client.KoogHttpClientException
import ai.koog.http.client.ktor.KtorKoogHttpClient
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.util.reflect.TypeInfo
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import org.atmosphere.ai.StreamIdleWatchdog
import java.time.Duration
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.reflect.KClass

/**
 * What one Atmosphere turn tells the transport of the client Atmosphere builds
 * ([AtmosphereOpenAILLMClient]): the turn's [StreamIdleWatchdog], fed every
 * line the provider sends (an SSE keep-alive comment included), and the read
 * timeout of the turn's calls, the application's stream idle bound plus
 * [KoogAgentRuntime.TRANSPORT_GRACE] ([Duration.ZERO], Koog's own setting,
 * when the bound is off). Carried in the coroutine context the turn runs in.
 */
internal class KoogTurnTransport(
    val watchdog: StreamIdleWatchdog?,
    val readTimeout: Duration
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<KoogTurnTransport>
}

/**
 * The [KoogHttpClient] behind the client Atmosphere builds.
 *
 * Koog's own Ktor client streams through Ktor's `sse {}` builder, which sends
 * the request from a coroutine of the `HttpClient`'s scope, not the caller's:
 * cancelling the caller (a turn's cancel, its idle bound) before the response
 * headers leaves that request running and its connection open. And Koog's
 * default engine (Apache 5, first found on its classpath) keeps the connection
 * after a cancel past the headers too. This client streams with
 * `preparePost(...).execute {}` on the caller's coroutine over a CIO engine,
 * whose call closes its socket when the call's job ends, so cancelling the
 * turn closes the connection before and after the headers. It reads the SSE
 * lines itself, so a keep-alive comment (`: ...`) is idle activity for the
 * turn, and it gives each call the turn's read timeout as a transport backstop.
 * Plain GETs and line streams go through Koog's [KtorKoogHttpClient] on the
 * same Ktor client.
 */
internal class AtmosphereKoogHttpClient(
    private val delegate: KtorKoogHttpClient
) : KoogHttpClient by delegate {

    private val ktor: HttpClient get() = delegate.ktorClient

    override suspend fun <T : Any, R : Any> post(
        path: String,
        requestBody: T,
        requestBodyType: KClass<T>,
        responseType: KClass<R>,
        parameters: Map<String, String>,
        headers: Map<String, String>
    ): R {
        val turn = currentCoroutineContext()[KoogTurnTransport]
        val response = ktor.post(path) {
            body(requestBody, requestBodyType)
            parameters.forEach { (key, value) -> parameter(key, value) }
            applyHeaders(headers)
            applyReadTimeout(turn)
        }
        if (!response.status.isSuccess()) {
            throw failure(response)
        }
        @Suppress("UNCHECKED_CAST")
        return if (responseType == String::class) response.bodyAsText() as R else response.body(TypeInfo(responseType))
    }

    override fun <T : Any, R : Any, O : Any> sse(
        path: String,
        requestBody: T,
        requestBodyType: KClass<T>,
        dataFilter: (String?) -> Boolean,
        decodeStreamingResponse: (String) -> R,
        processStreamingChunk: (R) -> O?,
        parameters: Map<String, String>,
        headers: Map<String, String>
    ): Flow<O> = flow {
        val turn = currentCoroutineContext()[KoogTurnTransport]
        try {
            ktor.preparePost(path) {
                body(requestBody, requestBodyType)
                parameters.forEach { (key, value) -> parameter(key, value) }
                applyHeaders(mapOf(
                    HttpHeaders.Accept to ContentType.Text.EventStream.toString(),
                    HttpHeaders.CacheControl to "no-cache"
                ) + headers)
                applyReadTimeout(turn)
            }.execute { response ->
                turn?.watchdog?.activity()
                if (!response.status.isSuccess()) {
                    throw failure(response)
                }
                val type = response.contentType()
                if (type == null || !type.match(ContentType.Text.EventStream)) {
                    throw KoogHttpClientException(delegate.clientName, response.status.value,
                        response.bodyAsText(), "Expected Content-Type text/event-stream but was $type", null)
                }
                val channel = response.bodyAsChannel()
                val data = StringBuilder()
                var hasData = false
                while (true) {
                    val line = channel.readUTF8Line() ?: break
                    // Every line is the provider talking, a keep-alive comment too.
                    turn?.watchdog?.activity()
                    when {
                        line.isEmpty() -> {
                            if (hasData) {
                                val event = data.toString()
                                data.setLength(0)
                                hasData = false
                                if (dataFilter(event)) {
                                    processStreamingChunk(decodeStreamingResponse(event.trim()))?.let { emit(it) }
                                }
                            }
                        }
                        line.startsWith(":") -> {}
                        line.startsWith("data:") -> {
                            if (hasData) data.append('\n')
                            data.append(line.substring(5).removePrefix(" "))
                            hasData = true
                        }
                        // event:, id:, retry: and unknown fields carry no data.
                        else -> {}
                    }
                }
                if (hasData) {
                    val event = data.toString()
                    if (dataFilter(event)) {
                        processStreamingChunk(decodeStreamingResponse(event.trim()))?.let { emit(it) }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: KoogHttpClientException) {
            throw e
        } catch (e: Exception) {
            throw KoogHttpClientException(delegate.clientName, null, null,
                "Exception during streaming: ${e.message}", e)
        }
    }

    private suspend fun failure(response: HttpResponse): KoogHttpClientException =
        KoogHttpClientException(delegate.clientName, response.status.value, response.bodyAsText(), null, null)

    private fun <T : Any> HttpRequestBuilder.body(requestBody: T, requestBodyType: KClass<T>) {
        if (requestBody is String) {
            setBody<String>(requestBody)
        } else {
            setBody(requestBody, TypeInfo(requestBodyType))
        }
    }

    private fun HttpRequestBuilder.applyHeaders(headers: Map<String, String>) {
        headers.forEach { (name, value) ->
            if (name.equals(HttpHeaders.ContentType, ignoreCase = true)) {
                contentType(ContentType.parse(value))
            } else {
                this.headers.remove(name)
                header(name, value)
            }
        }
    }

    private fun HttpRequestBuilder.applyReadTimeout(turn: KoogTurnTransport?) {
        val read = turn?.readTimeout ?: return
        if (read.isZero || read.isNegative) return
        timeout { socketTimeoutMillis = read.toMillis() }
    }

    /**
     * Creates the clients of one [AtmosphereOpenAILLMClient] on [base], the
     * CIO `HttpClient` that client created and closes.
     */
    class Factory(private val base: HttpClient) : KoogHttpClient.Factory {
        override fun create(
            clientName: String,
            baseUrl: String,
            headers: Map<String, String>,
            queryParameters: Map<String, String>,
            requestTimeoutMillis: Long,
            connectTimeoutMillis: Long,
            socketTimeoutMillis: Long,
            json: Json
        ): KoogHttpClient = AtmosphereKoogHttpClient(
            // withSse = false: this client streams SSE itself.
            KtorKoogHttpClient.Factory(base, false).create(clientName, baseUrl, headers, queryParameters,
                requestTimeoutMillis, connectTimeoutMillis, socketTimeoutMillis, json)
        )

        companion object {
            /** A CIO engine: its call closes the socket when the call's job ends. */
            fun newTransport(): HttpClient = HttpClient(CIO)
        }
    }
}
