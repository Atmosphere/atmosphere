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
package org.atmosphere.a2a.runtime;

import java.util.function.Consumer;

/**
 * Interface for handlers that support direct in-JVM dispatch without HTTP.
 * Implemented by {@link A2aHandler} so that local transports can avoid
 * reflection when invoking the protocol handler.
 */
public interface LocalDispatchable {

    /**
     * Dispatch a JSON-RPC request locally and return the JSON-RPC response.
     *
     * @param jsonRpcRequest the JSON-RPC 2.0 request body
     * @return the JSON-RPC 2.0 response string, or {@code null} for notifications
     */
    String dispatchLocal(String jsonRpcRequest);

    /**
     * Dispatch a streaming JSON-RPC request locally.
     *
     * @param jsonRpcRequest the JSON-RPC 2.0 request body
     * @param onToken        callback for each text token
     * @param onComplete     callback when execution finishes
     */
    void dispatchLocalStreaming(String jsonRpcRequest, Consumer<String> onToken,
                                Runnable onComplete);
}
