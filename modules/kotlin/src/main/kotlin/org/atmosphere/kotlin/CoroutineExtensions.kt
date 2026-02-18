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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import org.atmosphere.cpr.AtmosphereResource
import org.atmosphere.cpr.Broadcaster

/**
 * Coroutine-friendly extensions for Atmosphere's core types.
 */

/**
 * Suspending broadcast that waits for delivery to complete.
 *
 * ```kotlin
 * suspend fun handleMessage(broadcaster: Broadcaster, msg: String) {
 *     broadcaster.broadcastSuspend(msg)
 *     println("Delivered to all clients")
 * }
 * ```
 */
suspend fun Broadcaster.broadcastSuspend(message: Any): Any? {
    return withContext(Dispatchers.IO) {
        broadcast(message).get()
    }
}

/**
 * Suspending broadcast to a specific resource.
 */
suspend fun Broadcaster.broadcastSuspend(message: Any, resource: AtmosphereResource): Any? {
    return withContext(Dispatchers.IO) {
        broadcast(message, resource).get()
    }
}

/**
 * Suspending write to the resource's output.
 *
 * ```kotlin
 * suspend fun respond(resource: AtmosphereResource) {
 *     resource.writeSuspend("Hello from coroutine")
 * }
 * ```
 */
suspend fun AtmosphereResource.writeSuspend(data: String): AtmosphereResource {
    return withContext(Dispatchers.IO) {
        write(data)
    }
}

/**
 * Suspending binary write.
 */
suspend fun AtmosphereResource.writeSuspend(data: ByteArray): AtmosphereResource {
    return withContext(Dispatchers.IO) {
        write(data)
    }
}
