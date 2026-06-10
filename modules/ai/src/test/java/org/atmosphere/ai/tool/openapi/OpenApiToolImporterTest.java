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

import com.sun.net.httpserver.HttpServer;
import org.atmosphere.ai.tool.DefaultToolRegistry;
import org.atmosphere.ai.tool.ToolDefinition;
import org.atmosphere.ai.tool.ToolKind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenApiToolImporterTest {

    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<String> lastMethod = new AtomicReference<>();
    private final AtomicReference<String> lastUri = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastHeader = new AtomicReference<>();

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            lastMethod.set(exchange.getRequestMethod());
            lastUri.set(exchange.getRequestURI().toString());
            lastHeader.set(exchange.getRequestHeaders().getFirst("X-Tenant"));
            try (InputStream in = exchange.getRequestBody()) {
                lastBody.set(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            byte[] response;
            int status;
            if (exchange.getRequestURI().getPath().startsWith("/missing")) {
                status = 404;
                response = "not found".getBytes(StandardCharsets.UTF_8);
            } else {
                status = 200;
                response = ("{\"ok\":true,\"path\":\"" + exchange.getRequestURI().getPath() + "\"}")
                        .getBytes(StandardCharsets.UTF_8);
            }
            exchange.sendResponseHeaders(status, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private String spec() {
        return """
            {
              "openapi": "3.0.3",
              "servers": [{"url": "%s"}],
              "paths": {
                "/pets/{petId}": {
                  "get": {
                    "operationId": "getPet",
                    "summary": "Get a pet by id",
                    "parameters": [
                      {"name": "petId", "in": "path", "required": true, "schema": {"type": "integer"}},
                      {"name": "verbose", "in": "query", "required": false, "schema": {"type": "boolean"}},
                      {"name": "X-Tenant", "in": "header", "schema": {"type": "string"}}
                    ]
                  }
                },
                "/pets": {
                  "post": {
                    "operationId": "createPet",
                    "summary": "Create a pet",
                    "requestBody": {
                      "required": true,
                      "content": {"application/json": {"schema": {"$ref": "#/components/schemas/NewPet"}}}
                    }
                  }
                },
                "/missing": {"get": {"operationId": "missing", "summary": "always 404"}}
              },
              "components": {
                "schemas": {
                  "NewPet": {
                    "type": "object",
                    "required": ["name"],
                    "properties": {
                      "name": {"type": "string", "description": "pet name"},
                      "tag": {"type": "string"}
                    }
                  }
                }
              }
            }
            """.formatted(baseUrl);
    }

    private ToolDefinition tool(List<ToolDefinition> defs, String name) {
        return defs.stream().filter(d -> d.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void parsesOperationsIntoGovernedTools() {
        var defs = OpenApiToolImporter.fromSpec(spec(), OpenApiImportOptions.defaults());
        assertEquals(3, defs.size());

        var getPet = tool(defs, "get_pet");
        assertEquals("Get a pet by id", getPet.description());
        assertEquals(ToolKind.NETWORK, getPet.kind(),
                "imported HTTP tools default to NETWORK (sensitive, never auto-approved)");
        var petId = getPet.parameters().stream()
                .filter(p -> p.name().equals("petId")).findFirst().orElseThrow();
        assertEquals("integer", petId.type());
        assertTrue(petId.required(), "path params are always required");

        // requestBody $ref must be resolved and its properties flattened.
        var createPet = tool(defs, "create_pet");
        var name = createPet.parameters().stream()
                .filter(p -> p.name().equals("name")).findFirst().orElseThrow();
        assertTrue(name.required(), "name is in the schema's required[] list");
        assertTrue(createPet.parameters().stream().anyMatch(p -> p.name().equals("tag")));
    }

    @Test
    void getExecutorBuildsEncodedPathAndQuery() throws Exception {
        var defs = OpenApiToolImporter.fromSpec(spec(), OpenApiImportOptions.defaults());
        var result = tool(defs, "get_pet").executor()
                .execute(Map.of("petId", 42, "verbose", true, "X-Tenant", "acme"));

        assertEquals("GET", lastMethod.get());
        assertEquals("/pets/42?verbose=true", lastUri.get());
        assertEquals("acme", lastHeader.get(), "header param must be applied to the request");
        assertNotNull(result);
        assertTrue(result.toString().contains("\"ok\":true"));
    }

    @Test
    void postExecutorSendsJsonBody() throws Exception {
        var defs = OpenApiToolImporter.fromSpec(spec(), OpenApiImportOptions.defaults());
        tool(defs, "create_pet").executor().execute(Map.of("name", "Rex", "tag", "dog"));

        assertEquals("POST", lastMethod.get());
        assertEquals("/pets", lastUri.get());
        assertTrue(lastBody.get().contains("\"name\":\"Rex\""), "body: " + lastBody.get());
        assertTrue(lastBody.get().contains("\"tag\":\"dog\""), "body: " + lastBody.get());
    }

    @Test
    void nonSuccessStatusIsReturnedNotThrown() throws Exception {
        var defs = OpenApiToolImporter.fromSpec(spec(), OpenApiImportOptions.defaults());
        var result = tool(defs, "missing").executor().execute(Map.of());
        assertTrue(result.toString().startsWith("HTTP 404"), "got: " + result);
    }

    @Test
    void importIntoRegistersGovernedTools() {
        var registry = new DefaultToolRegistry();
        var count = OpenApiToolImporter.importInto(registry, spec(), OpenApiImportOptions.defaults());
        assertEquals(3, count);
        assertTrue(registry.getTool("get_pet").isPresent(),
                "imported tools must land in the registry so they ride the governance path");
        assertTrue(registry.getTool("create_pet").isPresent());
    }

    @Test
    void writeApprovalIsAppliedWhenRequested() {
        var defs = OpenApiToolImporter.fromSpec(spec(),
                OpenApiImportOptions.builder().approvalForWrites(true).build());
        assertTrue(tool(defs, "create_pet").requiresApproval(),
                "POST must require approval when approvalForWrites is set");
        assertFalse(tool(defs, "get_pet").requiresApproval(),
                "GET is read-only — no approval");
    }

    @Test
    void parsesYamlSpec() {
        var yaml = """
            openapi: 3.0.3
            servers:
              - url: %s
            paths:
              /ping:
                get:
                  operationId: ping
                  summary: health check
            """.formatted(baseUrl);
        var defs = OpenApiToolImporter.fromSpec(yaml, OpenApiImportOptions.defaults());
        assertEquals(1, defs.size());
        assertEquals("ping", defs.get(0).name());
    }

    @Test
    void prefixDisambiguatesToolNames() {
        var defs = OpenApiToolImporter.fromSpec(spec(),
                OpenApiImportOptions.builder().toolNamePrefix("petstore_").build());
        assertTrue(defs.stream().anyMatch(d -> d.name().equals("petstore_get_pet")));
    }
}
