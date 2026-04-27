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
package org.atmosphere.a2a.types;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Single agent endpoint surfaced on {@link AgentCard#supportedInterfaces()}.
 * The {@code protocolBinding} string is open-form; the spec's officially
 * supported core values are {@code JSONRPC}, {@code GRPC}, and {@code HTTP+JSON}.
 *
 * <p>Defined in A2A v1.0.0; replaces the pre-1.0 single top-level
 * {@code AgentCard.url} field by allowing an agent to advertise multiple
 * protocol bindings simultaneously.</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentInterface(
    String url,
    String protocolBinding,
    String tenant,
    String protocolVersion
) {
    public static final String JSONRPC = "JSONRPC";
    public static final String GRPC = "GRPC";
    public static final String HTTP_JSON = "HTTP+JSON";

    public AgentInterface(String url, String protocolBinding, String protocolVersion) {
        this(url, protocolBinding, null, protocolVersion);
    }
}
