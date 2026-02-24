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
 * Default no-op implementation of {@link GrpcHandler}.
 * Extend this class and override only the methods you need.
 */
public class GrpcHandlerAdapter implements GrpcHandler {

    @Override
    public void onOpen(GrpcChannel channel) {
    }

    @Override
    public void onMessage(GrpcChannel channel, String message) {
    }

    @Override
    public void onBinaryMessage(GrpcChannel channel, byte[] data) {
    }

    @Override
    public void onClose(GrpcChannel channel) {
    }

    @Override
    public void onError(GrpcChannel channel, Throwable t) {
    }
}
