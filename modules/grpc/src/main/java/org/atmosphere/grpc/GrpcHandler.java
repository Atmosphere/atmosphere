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
package org.atmosphere.grpc;

/**
 * User-facing interface for handling gRPC lifecycle events.
 * Patterned after WebSocketHandler for consistency.
 */
public interface GrpcHandler {

    void onOpen(GrpcChannel channel);

    void onMessage(GrpcChannel channel, String message);

    void onBinaryMessage(GrpcChannel channel, byte[] data);

    void onClose(GrpcChannel channel);

    void onError(GrpcChannel channel, Throwable t);
}
