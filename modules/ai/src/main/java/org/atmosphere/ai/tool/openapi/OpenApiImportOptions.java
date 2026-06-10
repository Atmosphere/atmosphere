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
package org.atmosphere.ai.tool.openapi;

import org.atmosphere.ai.tool.ToolKind;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * Configuration for {@link OpenApiToolImporter}. Built via {@link #builder()};
 * sensible defaults make {@code OpenApiImportOptions.builder().build()} usable
 * for a public, no-auth spec whose own {@code servers[0].url} is reachable.
 *
 * @param baseUrl           overrides the spec's {@code servers[0].url}; when
 *                          {@code null} the importer uses the first server URL
 *                          declared in the spec
 * @param headers           static headers added to every request (e.g.
 *                          {@code Authorization}); never {@code null}
 * @param toolNamePrefix    prepended to every generated tool name (e.g.
 *                          {@code "petstore_"}) to avoid collisions when
 *                          importing several specs; never {@code null}
 * @param includeMethods    HTTP methods to import (upper-case); operations using
 *                          other methods are skipped
 * @param requestTimeout    per-call timeout for the generated tool executors
 * @param maxResponseBytes  cap on the response body read into memory per call
 *                          (bounds a hostile/huge response); the result is
 *                          truncated past this
 * @param kind              {@link ToolKind} assigned to every imported tool;
 *                          defaults to {@link ToolKind#NETWORK} (sensitive,
 *                          never auto-approved)
 * @param approvalForWrites when {@code true}, operations using a mutating method
 *                          (POST/PUT/PATCH/DELETE) are marked
 *                          {@code requiresApproval} so the HITL gate fires
 *                          before they run
 * @param httpClient        client used by the generated executors; {@code null}
 *                          builds a default {@link HttpClient}
 */
public record OpenApiImportOptions(
        String baseUrl,
        Map<String, String> headers,
        String toolNamePrefix,
        Set<String> includeMethods,
        Duration requestTimeout,
        int maxResponseBytes,
        ToolKind kind,
        boolean approvalForWrites,
        HttpClient httpClient) {

    /** Default methods imported when not overridden. */
    public static final Set<String> DEFAULT_METHODS =
            Set.of("GET", "POST", "PUT", "PATCH", "DELETE");

    public OpenApiImportOptions {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        toolNamePrefix = toolNamePrefix == null ? "" : toolNamePrefix;
        includeMethods = includeMethods == null || includeMethods.isEmpty()
                ? DEFAULT_METHODS
                : Set.copyOf(includeMethods);
        requestTimeout = requestTimeout == null ? Duration.ofSeconds(30) : requestTimeout;
        if (maxResponseBytes <= 0) {
            maxResponseBytes = 1 << 20; // 1 MiB
        }
        kind = kind == null ? ToolKind.NETWORK : kind;
    }

    /** Defaults suitable for a public, self-describing spec. */
    public static OpenApiImportOptions defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String baseUrl;
        private Map<String, String> headers = Map.of();
        private String toolNamePrefix = "";
        private Set<String> includeMethods = DEFAULT_METHODS;
        private Duration requestTimeout = Duration.ofSeconds(30);
        private int maxResponseBytes = 1 << 20;
        private ToolKind kind = ToolKind.NETWORK;
        private boolean approvalForWrites;
        private HttpClient httpClient;

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public Builder headers(Map<String, String> headers) {
            this.headers = headers;
            return this;
        }

        public Builder header(String name, String value) {
            var merged = new java.util.HashMap<>(this.headers);
            merged.put(name, value);
            this.headers = merged;
            return this;
        }

        public Builder toolNamePrefix(String prefix) {
            this.toolNamePrefix = prefix;
            return this;
        }

        public Builder includeMethods(Set<String> methods) {
            this.includeMethods = methods;
            return this;
        }

        public Builder requestTimeout(Duration timeout) {
            this.requestTimeout = timeout;
            return this;
        }

        public Builder maxResponseBytes(int bytes) {
            this.maxResponseBytes = bytes;
            return this;
        }

        public Builder kind(ToolKind kind) {
            this.kind = kind;
            return this;
        }

        public Builder approvalForWrites(boolean approvalForWrites) {
            this.approvalForWrites = approvalForWrites;
            return this;
        }

        public Builder httpClient(HttpClient httpClient) {
            this.httpClient = httpClient;
            return this;
        }

        public OpenApiImportOptions build() {
            return new OpenApiImportOptions(baseUrl, headers, toolNamePrefix, includeMethods,
                    requestTimeout, maxResponseBytes, kind, approvalForWrites, httpClient);
        }
    }
}
