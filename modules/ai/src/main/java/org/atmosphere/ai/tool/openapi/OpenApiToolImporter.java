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

import org.atmosphere.ai.tool.ToolDefinition;
import org.atmosphere.ai.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Imports an OpenAPI 3.x specification (JSON or YAML) and turns every operation
 * into a governed Atmosphere {@link ToolDefinition} whose executor performs the
 * corresponding HTTP call.
 *
 * <p>Because the output is an ordinary {@link ToolDefinition} registered in the
 * {@link ToolRegistry}, the imported tools automatically ride the same
 * <strong>policy-admission gate and plan-and-verify</strong> path as
 * hand-written {@code @AiTool} methods — there is no separate, ungoverned
 * code path. That is the differentiator over gateway products that intercept
 * imported REST tools with a bolt-on policy engine.</p>
 *
 * <h2>Scope</h2>
 * Supports OpenAPI 3.x with local ({@code #/components/...}) {@code $ref}
 * resolution, path/query/header parameters, and a JSON request body whose
 * top-level properties are flattened into tool parameters (a non-object body is
 * exposed as a single {@code body} parameter). Cookie parameters and remote
 * {@code $ref}s are skipped with a log line. Each generated tool calls the
 * resolved server URL (or {@link OpenApiImportOptions#baseUrl()} when set),
 * URL-encoding every dynamic path and query value at the boundary.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * int count = OpenApiToolImporter.importInto(registry, specYaml,
 *         OpenApiImportOptions.builder()
 *                 .baseUrl("https://api.example.com")
 *                 .header("Authorization", "Bearer " + token)
 *                 .approvalForWrites(true)
 *                 .build());
 * }</pre>
 */
public final class OpenApiToolImporter {

    private static final Logger logger = LoggerFactory.getLogger(OpenApiToolImporter.class);
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final HttpClient DEFAULT_CLIENT = HttpClient.newHttpClient();
    private static final List<String> METHODS =
            List.of("get", "put", "post", "delete", "patch");
    private static final Set<String> MUTATING = Set.of("POST", "PUT", "PATCH", "DELETE");

    private OpenApiToolImporter() {
    }

    /** Parse a spec string (JSON or YAML) into governed tool definitions. */
    public static List<ToolDefinition> fromSpec(String spec, OpenApiImportOptions options) {
        var opts = options == null ? OpenApiImportOptions.defaults() : options;
        var root = readSpec(spec);
        var baseUrl = opts.baseUrl() != null ? opts.baseUrl() : firstServerUrl(root);
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException(
                    "OpenAPI import needs a base URL: the spec declares no servers[].url "
                            + "and OpenApiImportOptions.baseUrl() was not set");
        }
        baseUrl = stripTrailingSlash(baseUrl);

        var paths = root.get("paths");
        if (paths == null || !paths.isObject()) {
            logger.warn("OpenAPI spec has no 'paths' object — nothing to import");
            return List.of();
        }

        var client = opts.httpClient() != null ? opts.httpClient() : DEFAULT_CLIENT;
        var defs = new ArrayList<ToolDefinition>();
        var usedNames = new HashSet<String>();
        for (var pathEntry : paths.properties()) {
            var pathTemplate = pathEntry.getKey();
            var pathItem = pathEntry.getValue();
            if (pathItem == null || !pathItem.isObject()) {
                continue;
            }
            var sharedParams = pathItem.get("parameters");
            for (var method : METHODS) {
                if (!opts.includeMethods().contains(method.toUpperCase(Locale.ROOT))) {
                    continue;
                }
                var op = pathItem.get(method);
                if (op == null || !op.isObject()) {
                    continue;
                }
                try {
                    defs.add(buildTool(root, baseUrl, pathTemplate, method, op,
                            sharedParams, opts, client, usedNames));
                } catch (RuntimeException e) {
                    logger.warn("Skipping OpenAPI operation {} {}: {}",
                            method.toUpperCase(Locale.ROOT), pathTemplate, e.getMessage());
                }
            }
        }
        logger.info("Imported {} tool(s) from OpenAPI spec (base URL {})", defs.size(), baseUrl);
        return List.copyOf(defs);
    }

    /** Fetch a spec over HTTP and import it. */
    public static List<ToolDefinition> fromUrl(URI specUrl, OpenApiImportOptions options) {
        var opts = options == null ? OpenApiImportOptions.defaults() : options;
        var client = opts.httpClient() != null ? opts.httpClient() : DEFAULT_CLIENT;
        var builder = HttpRequest.newBuilder(specUrl).GET().timeout(opts.requestTimeout());
        opts.headers().forEach(builder::header);
        try {
            var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("spec fetch returned HTTP " + response.statusCode());
            }
            return fromSpec(response.body(), opts);
        } catch (IOException e) {
            throw new IllegalStateException("failed to fetch OpenAPI spec from " + specUrl, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted fetching OpenAPI spec from " + specUrl, e);
        }
    }

    /**
     * Import a spec and register every generated tool into {@code registry}.
     *
     * @return the number of tools registered
     */
    public static int importInto(ToolRegistry registry, String spec, OpenApiImportOptions options) {
        var defs = fromSpec(spec, options);
        var registered = 0;
        for (var def : defs) {
            try {
                registry.register(def);
                registered++;
            } catch (IllegalArgumentException e) {
                logger.warn("Tool '{}' already registered — skipping OpenAPI import for it", def.name());
            }
        }
        return registered;
    }

    // --- parsing -------------------------------------------------------------

    static JsonNode readSpec(String spec) {
        if (spec == null || spec.isBlank()) {
            throw new IllegalArgumentException("OpenAPI spec is empty");
        }
        var trimmed = spec.stripLeading();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return MAPPER.readTree(spec);
        }
        // YAML — snakeyaml loads to Map/List/scalar, then we project to JsonNode.
        var loaded = new Yaml().load(spec);
        return MAPPER.valueToTree(loaded);
    }

    private static ToolDefinition buildTool(JsonNode root, String baseUrl, String pathTemplate,
                                            String method, JsonNode op, JsonNode sharedParams,
                                            OpenApiImportOptions opts, HttpClient client,
                                            Set<String> usedNames) {
        var name = uniqueName(opts.toolNamePrefix() + toolName(op, method, pathTemplate), usedNames);
        var description = description(op, method, pathTemplate);
        var builder = ToolDefinition.builder(name, description)
                .returnType("string")
                .kind(opts.kind());
        var locations = new LinkedHashMap<String, ParamLocation>();

        collectParameters(root, sharedParams, builder, locations);
        collectParameters(root, op.get("parameters"), builder, locations);
        collectRequestBody(root, op.get("requestBody"), builder, locations);

        var upper = method.toUpperCase(Locale.ROOT);
        if (opts.approvalForWrites() && MUTATING.contains(upper)) {
            builder.requiresApproval("Approve " + upper + " " + pathTemplate);
        }
        builder.executor(new HttpOperationExecutor(
                client, upper, baseUrl, pathTemplate, locations, opts));
        return builder.build();
    }

    private static void collectParameters(JsonNode root, JsonNode paramsNode,
                                          ToolDefinition.Builder builder,
                                          Map<String, ParamLocation> locations) {
        if (paramsNode == null || !paramsNode.isArray()) {
            return;
        }
        for (var raw : paramsNode) {
            var param = deref(root, raw);
            if (param == null || !param.isObject()) {
                continue;
            }
            var in = text(param.get("in"), "");
            var name = text(param.get("name"), null);
            if (name == null || name.isBlank()) {
                continue;
            }
            var location = switch (in) {
                case "path" -> ParamLocation.PATH;
                case "query" -> ParamLocation.QUERY;
                case "header" -> ParamLocation.HEADER;
                default -> null;
            };
            if (location == null) {
                logger.debug("Skipping unsupported parameter location '{}' for '{}'", in, name);
                continue;
            }
            var schema = deref(root, param.get("schema"));
            var type = schemaType(schema);
            var required = "path".equals(in) || bool(param.get("required"));
            var desc = text(param.get("description"), name);
            builder.parameter(name, desc, type, required);
            locations.put(name, location);
        }
    }

    private static void collectRequestBody(JsonNode root, JsonNode requestBodyNode,
                                           ToolDefinition.Builder builder,
                                           Map<String, ParamLocation> locations) {
        var rb = deref(root, requestBodyNode);
        if (rb == null || !rb.isObject()) {
            return;
        }
        var content = rb.get("content");
        if (content == null || !content.isObject()) {
            return;
        }
        var json = content.get("application/json");
        if (json == null) {
            // Fall back to the first declared media type.
            var first = content.properties().iterator();
            if (!first.hasNext()) {
                return;
            }
            json = first.next().getValue();
        }
        var schema = deref(root, json.get("schema"));
        if (schema == null) {
            return;
        }
        var bodyRequired = bool(rb.get("required"));
        if ("object".equals(schemaType(schema)) && schema.has("properties")
                && schema.get("properties").isObject()) {
            var requiredSet = stringArray(schema.get("required"));
            for (var prop : schema.get("properties").properties()) {
                var propName = prop.getKey();
                var propSchema = deref(root, prop.getValue());
                var type = schemaType(propSchema);
                var desc = propSchema != null
                        ? text(propSchema.get("description"), propName) : propName;
                builder.parameter(propName, desc, type, requiredSet.contains(propName));
                locations.put(propName, ParamLocation.BODY);
            }
        } else {
            builder.parameter("body", "request body (JSON)", schemaType(schema), bodyRequired);
            locations.put("body", ParamLocation.BODY_RAW);
        }
    }

    // --- $ref + node helpers -------------------------------------------------

    private static JsonNode deref(JsonNode root, JsonNode node) {
        var hops = 0;
        while (node != null && node.isObject() && node.has("$ref")
                && node.get("$ref").isString()) {
            var ref = node.get("$ref").stringValue();
            if (!ref.startsWith("#/")) {
                logger.debug("Skipping non-local $ref '{}'", ref);
                return null;
            }
            node = resolvePointer(root, ref);
            if (++hops > 20) {
                logger.warn("Aborting $ref resolution after 20 hops (cycle?)");
                return null;
            }
        }
        return node;
    }

    private static JsonNode resolvePointer(JsonNode root, String ref) {
        var cur = root;
        for (var seg : ref.substring(2).split("/")) {
            if (cur == null) {
                return null;
            }
            cur = cur.get(seg.replace("~1", "/").replace("~0", "~"));
        }
        return cur;
    }

    private static String schemaType(JsonNode schema) {
        if (schema != null && schema.has("type") && schema.get("type").isString()) {
            return schema.get("type").stringValue();
        }
        return "string";
    }

    private static Set<String> stringArray(JsonNode node) {
        if (node == null || !node.isArray()) {
            return Set.of();
        }
        var out = new HashSet<String>();
        for (var el : node) {
            if (el.isString()) {
                out.add(el.stringValue());
            }
        }
        return out;
    }

    private static String text(JsonNode node, String fallback) {
        return node != null && node.isString() ? node.stringValue() : fallback;
    }

    private static boolean bool(JsonNode node) {
        return node != null && node.isBoolean() && node.booleanValue();
    }

    private static String firstServerUrl(JsonNode root) {
        var servers = root.get("servers");
        if (servers != null && servers.isArray()) {
            for (var server : servers) {
                var url = text(server.get("url"), null);
                if (url != null && !url.isBlank()) {
                    return url;
                }
            }
        }
        return null;
    }

    private static String toolName(JsonNode op, String method, String pathTemplate) {
        var opId = text(op.get("operationId"), null);
        var raw = opId != null ? opId : method + "_" + pathTemplate;
        return sanitize(raw);
    }

    private static String description(JsonNode op, String method, String pathTemplate) {
        var summary = text(op.get("summary"), null);
        if (summary != null && !summary.isBlank()) {
            return summary;
        }
        var desc = text(op.get("description"), null);
        if (desc != null && !desc.isBlank()) {
            return desc;
        }
        return method.toUpperCase(Locale.ROOT) + " " + pathTemplate;
    }

    private static String sanitize(String raw) {
        // Split camelCase boundaries (getPet -> get_Pet) before lowercasing so
        // the snake_case tool name keeps the operationId's word structure.
        var spaced = raw.replaceAll("([a-z0-9])([A-Z])", "$1_$2");
        var cleaned = spaced.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        return cleaned.isEmpty() ? "operation" : cleaned;
    }

    private static String uniqueName(String base, Set<String> used) {
        var candidate = base;
        var i = 2;
        while (!used.add(candidate)) {
            candidate = base + "_" + i++;
        }
        return candidate;
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** Where an OpenAPI parameter is carried in the HTTP request. */
    private enum ParamLocation {
        PATH, QUERY, HEADER, BODY, BODY_RAW
    }

    /**
     * {@link org.atmosphere.ai.tool.ToolExecutor} that issues the HTTP call for
     * a single OpenAPI operation, URL-encoding every dynamic path/query value at
     * the boundary and bounding the response read.
     */
    private record HttpOperationExecutor(HttpClient client, String method, String baseUrl,
                                         String pathTemplate, Map<String, ParamLocation> locations,
                                         OpenApiImportOptions options)
            implements org.atmosphere.ai.tool.ToolExecutor {

        @Override
        public Object execute(Map<String, Object> arguments) throws Exception {
            var args = arguments == null ? Map.<String, Object>of() : arguments;
            var path = pathTemplate;
            var query = new StringBuilder();
            var body = new LinkedHashMap<String, Object>();
            Object rawBody = null;

            for (var entry : locations.entrySet()) {
                var name = entry.getKey();
                if (!args.containsKey(name) || args.get(name) == null) {
                    continue;
                }
                var value = args.get(name);
                switch (entry.getValue()) {
                    case PATH -> path = path.replace("{" + name + "}", encodePath(str(value)));
                    case QUERY -> {
                        query.append(query.isEmpty() ? "" : "&")
                                .append(encode(name)).append("=").append(encode(str(value)));
                    }
                    case BODY -> body.put(name, value);
                    case BODY_RAW -> rawBody = value;
                    case HEADER -> { /* applied on the request builder below */ }
                }
            }

            var url = baseUrl + path + (query.isEmpty() ? "" : "?" + query);
            var requestBuilder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(options.requestTimeout());
            options.headers().forEach(requestBuilder::header);
            for (var entry : locations.entrySet()) {
                if (entry.getValue() == ParamLocation.HEADER
                        && args.get(entry.getKey()) != null) {
                    requestBuilder.header(entry.getKey(), str(args.get(entry.getKey())));
                }
            }

            HttpRequest.BodyPublisher publisher;
            if (rawBody != null) {
                publisher = HttpRequest.BodyPublishers.ofString(
                        rawBody instanceof String s ? s : MAPPER.writeValueAsString(rawBody));
                requestBuilder.header("Content-Type", "application/json");
            } else if (!body.isEmpty()) {
                publisher = HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body));
                requestBuilder.header("Content-Type", "application/json");
            } else {
                publisher = HttpRequest.BodyPublishers.noBody();
            }
            requestBuilder.method(method, publisher);

            var response = client.send(requestBuilder.build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            var payload = readBounded(response.body(), options.maxResponseBytes());
            var status = response.statusCode();
            return status / 100 == 2 ? payload : "HTTP " + status + ": " + payload;
        }

        private static String str(Object value) {
            return value == null ? "" : value.toString();
        }

        private static String encode(String value) {
            return URLEncoder.encode(value, StandardCharsets.UTF_8);
        }

        private static String encodePath(String value) {
            // application/x-www-form-urlencoded turns space into '+', which is
            // wrong inside a path segment — normalize it back to %20.
            return encode(value).replace("+", "%20");
        }

        private static String readBounded(InputStream in, int max) throws IOException {
            try (in) {
                var bytes = in.readNBytes(max);
                var text = new String(bytes, StandardCharsets.UTF_8);
                // Drain any remainder so the connection can be reused, but do
                // not retain it — the response is capped on purpose.
                if (in.read() != -1) {
                    in.transferTo(OutputStream.nullOutputStream());
                    return text + "\n…[truncated at " + max + " bytes]";
                }
                return text;
            }
        }
    }
}
