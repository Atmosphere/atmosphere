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
package org.atmosphere.mcp.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * JSON-RPC 2.0 message types used by MCP.
 */
public final class JsonRpc {

    public static final String VERSION = "2.0";

    private JsonRpc() {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Request(
            @JsonProperty("jsonrpc") String jsonrpc,
            @JsonProperty("id") Object id,
            @JsonProperty("method") String method,
            @JsonProperty("params") Object params
    ) {
        public Request(Object id, String method, Object params) {
            this(VERSION, id, method, params);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Response(
            @JsonProperty("jsonrpc") String jsonrpc,
            @JsonProperty("id") Object id,
            @JsonProperty("result") Object result,
            @JsonProperty("error") Error error
    ) {
        public static Response success(Object id, Object result) {
            return new Response(VERSION, id, result, null);
        }

        public static Response error(Object id, int code, String message) {
            return new Response(VERSION, id, null, new Error(code, message, null));
        }

        public static Response error(Object id, int code, String message, Object data) {
            return new Response(VERSION, id, null, new Error(code, message, data));
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Notification(
            @JsonProperty("jsonrpc") String jsonrpc,
            @JsonProperty("method") String method,
            @JsonProperty("params") Object params
    ) {
        public Notification(String method, Object params) {
            this(VERSION, method, params);
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Error(
            @JsonProperty("code") int code,
            @JsonProperty("message") String message,
            @JsonProperty("data") Object data
    ) {}

    // Standard JSON-RPC error codes
    public static final int PARSE_ERROR = -32700;
    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
    public static final int INTERNAL_ERROR = -32603;
}
