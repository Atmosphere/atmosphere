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
package org.atmosphere.kotlin

import org.atmosphere.cpr.AtmosphereHandler
import org.atmosphere.cpr.AtmosphereResource
import org.atmosphere.cpr.AtmosphereResourceEvent
import java.io.IOException

/**
 * Kotlin DSL for building [AtmosphereHandler] instances.
 *
 * ```kotlin
 * val handler = atmosphere {
 *     onConnect { resource ->
 *         resource.broadcaster.broadcast("${resource.uuid()} joined")
 *     }
 *     onMessage { resource, message ->
 *         resource.broadcaster.broadcast(message)
 *     }
 *     onDisconnect { resource ->
 *         resource.broadcaster.broadcast("${resource.uuid()} left")
 *     }
 * }
 * ```
 */
@DslMarker
annotation class AtmosphereDsl

@AtmosphereDsl
class AtmosphereHandlerBuilder {

    private var connectHandler: ((AtmosphereResource) -> Unit)? = null
    private var messageHandler: ((AtmosphereResource, String) -> Unit)? = null
    private var disconnectHandler: ((AtmosphereResource) -> Unit)? = null
    private var timeoutHandler: ((AtmosphereResource) -> Unit)? = null
    private var resumeHandler: ((AtmosphereResource) -> Unit)? = null

    /**
     * Called when a client connects and the resource is suspended.
     */
    fun onConnect(handler: (AtmosphereResource) -> Unit) {
        connectHandler = handler
    }

    /**
     * Called when a message is received from a client.
     */
    fun onMessage(handler: (AtmosphereResource, String) -> Unit) {
        messageHandler = handler
    }

    /**
     * Called when a client disconnects.
     */
    fun onDisconnect(handler: (AtmosphereResource) -> Unit) {
        disconnectHandler = handler
    }

    /**
     * Called when the resource times out.
     */
    fun onTimeout(handler: (AtmosphereResource) -> Unit) {
        timeoutHandler = handler
    }

    /**
     * Called when the resource is resumed.
     */
    fun onResume(handler: (AtmosphereResource) -> Unit) {
        resumeHandler = handler
    }

    internal fun build(): AtmosphereHandler = DslAtmosphereHandler(
        connectHandler = connectHandler,
        messageHandler = messageHandler,
        disconnectHandler = disconnectHandler,
        timeoutHandler = timeoutHandler,
        resumeHandler = resumeHandler
    )
}

private class DslAtmosphereHandler(
    private val connectHandler: ((AtmosphereResource) -> Unit)?,
    private val messageHandler: ((AtmosphereResource, String) -> Unit)?,
    private val disconnectHandler: ((AtmosphereResource) -> Unit)?,
    private val timeoutHandler: ((AtmosphereResource) -> Unit)?,
    private val resumeHandler: ((AtmosphereResource) -> Unit)?
) : AtmosphereHandler {

    @Throws(IOException::class)
    override fun onRequest(resource: AtmosphereResource) {
        val method = resource.request.method
        if ("GET".equals(method, ignoreCase = true)) {
            connectHandler?.invoke(resource)
            resource.suspend()
        } else {
            val body = resource.request.reader.readText()
            messageHandler?.invoke(resource, body)
        }
    }

    @Throws(IOException::class)
    override fun onStateChange(event: AtmosphereResourceEvent) {
        val resource = event.resource
        when {
            event.isClosedByClient || event.isClosedByApplication -> {
                disconnectHandler?.invoke(resource)
            }
            event.isResumedOnTimeout -> {
                timeoutHandler?.invoke(resource)
            }
            event.isResuming -> {
                resumeHandler?.invoke(resource)
            }
            event.message != null -> {
                resource.write(event.message.toString())
            }
        }
    }

    override fun destroy() {
        // no-op
    }
}

/**
 * Creates an [AtmosphereHandler] using the Kotlin DSL.
 *
 * ```kotlin
 * val handler = atmosphere {
 *     onConnect { resource -> println("Connected: ${resource.uuid()}") }
 *     onMessage { resource, msg -> resource.broadcaster.broadcast(msg) }
 * }
 * framework.addAtmosphereHandler("/chat", handler)
 * ```
 */
fun atmosphere(init: AtmosphereHandlerBuilder.() -> Unit): AtmosphereHandler {
    return AtmosphereHandlerBuilder().apply(init).build()
}
