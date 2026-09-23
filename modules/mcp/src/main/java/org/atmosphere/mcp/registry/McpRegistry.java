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
package org.atmosphere.mcp.registry;

import org.atmosphere.mcp.annotation.McpComplete;
import org.atmosphere.mcp.annotation.McpParam;
import org.atmosphere.mcp.annotation.McpPrompt;
import org.atmosphere.mcp.annotation.McpResource;
import org.atmosphere.mcp.annotation.McpTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Registry for MCP tools, resources, and prompts. Supports both annotation-based
 * discovery (via {@link #scan(Object)}) and programmatic registration
 * (via {@link #registerTool(String, String, List, ToolHandler)}).
 */
public final class McpRegistry {

    private static final Logger logger = LoggerFactory.getLogger(McpRegistry.class);

    /**
     * Functional interface for dynamically registered tools.
     * Receives a map of argument name → value, returns the tool result.
     *
     * <p>Handlers that need the authenticated caller's identity — to gate
     * writes, scope tenant lookups, etc. — implement the sibling
     * {@link IdentityAwareToolHandler}. The registry invokes whichever
     * SAM the registered instance conforms to; existing single-arg
     * lambdas keep compiling unchanged.</p>
     */
    @FunctionalInterface
    public interface ToolHandler {
        Object execute(Map<String, Object> arguments) throws Exception;
    }

    /**
     * Identity-aware tool handler. {@code McpProtocolHandler} invokes this
     * variant when the registered handler implements it, passing the
     * servlet-resolved principal name ({@code null} for anonymous
     * callers). Used by {@code AdminMcpBridge} so write tools can forward
     * the caller identity to {@code ControlAuthorizer.authorize}.
     */
    @FunctionalInterface
    public interface IdentityAwareToolHandler extends ToolHandler {
        /** Execute with the caller's principal name ({@code null} when anonymous). */
        Object execute(Map<String, Object> arguments, String principal) throws Exception;

        /** Default delegates to the identity-aware form with principal=null. */
        @Override
        default Object execute(Map<String, Object> arguments) throws Exception {
            return execute(arguments, null);
        }
    }

    /**
     * Functional interface for dynamically registered resources.
     * Receives a map of argument name → value, returns the resource content.
     */
    @FunctionalInterface
    public interface ResourceHandler {
        Object read(Map<String, Object> arguments) throws Exception;
    }

    /**
     * Functional interface for dynamically registered prompts.
     * Receives a map of argument name → value, returns prompt messages.
     */
    @FunctionalInterface
    public interface PromptHandler {
        Object get(Map<String, Object> arguments) throws Exception;
    }

    /**
     * Metadata for a registered MCP tool.
     */
    public record ToolEntry(String name, String description, Method method, Object instance,
                            List<ParamEntry> params, ToolHandler handler) {
        /** Annotation-based constructor (method + instance). */
        public ToolEntry(String name, String description, Method method, Object instance,
                         List<ParamEntry> params) {
            this(name, description, method, instance, params, null);
        }

        /** Programmatic constructor (handler lambda). */
        public ToolEntry(String name, String description, List<ParamEntry> params,
                         ToolHandler handler) {
            this(name, description, null, null, params, handler);
        }

        /** Returns true if this tool uses a programmatic handler. */
        public boolean isDynamic() {
            return handler != null;
        }
    }

    /**
     * Metadata for a registered MCP resource.
     */
    public record ResourceEntry(String uri, String name, String description, String mimeType,
                                Method method, Object instance, List<ParamEntry> params,
                                ResourceHandler handler) {
        /** Annotation-based constructor. */
        public ResourceEntry(String uri, String name, String description, String mimeType,
                             Method method, Object instance, List<ParamEntry> params) {
            this(uri, name, description, mimeType, method, instance, params, null);
        }

        /** Programmatic constructor. */
        public ResourceEntry(String uri, String name, String description, String mimeType,
                             List<ParamEntry> params, ResourceHandler handler) {
            this(uri, name, description, mimeType, null, null, params, handler);
        }

        public boolean isDynamic() {
            return handler != null;
        }
    }

    /**
     * Metadata for a registered MCP prompt.
     */
    public record PromptEntry(String name, String description, Method method, Object instance,
                              List<ParamEntry> params, PromptHandler handler) {
        /** Annotation-based constructor. */
        public PromptEntry(String name, String description, Method method, Object instance,
                           List<ParamEntry> params) {
            this(name, description, method, instance, params, null);
        }

        /** Programmatic constructor. */
        public PromptEntry(String name, String description, List<ParamEntry> params,
                           PromptHandler handler) {
            this(name, description, null, null, params, handler);
        }

        public boolean isDynamic() {
            return handler != null;
        }
    }

    /**
     * Metadata for a method parameter.
     *
     * <p>{@code schema} carries the structural JSON-Schema facets that
     * {@code type} alone cannot express — the closed value set of an enum, the
     * element type of an array, the nested properties of an object. It is
     * empty for a plain scalar parameter, in which case
     * {@link #inputSchema(ToolEntry)} emits exactly the flat
     * {@code type}/{@code description} pair it always did.</p>
     *
     * @param name        parameter name as exposed to the MCP client
     * @param description human-readable description
     * @param required    whether the client must supply this parameter
     * @param type        the Java type the parameter binds to
     * @param schema      extra JSON-Schema facets ({@code enum}, {@code items},
     *                    {@code properties}, ...); never {@code null}
     */
    public record ParamEntry(String name, String description, boolean required, Class<?> type,
                             Map<String, Object> schema) {
        public ParamEntry {
            schema = schema == null ? Map.of() : Map.copyOf(schema);
        }

        /** Flat-parameter constructor: no structural facets. */
        public ParamEntry(String name, String description, boolean required, Class<?> type) {
            this(name, description, required, type, Map.of());
        }
    }

    /**
     * MCP 2025-06-18 / 2025-11-25 spec extensions: human-friendly display
     * {@code title}, optional {@code iconUrl}, and an open {@code _meta}
     * passthrough blob. Carried as a sidecar to the {@link ToolEntry} /
     * {@link ResourceEntry} / {@link PromptEntry} records so existing
     * record signatures (and their many constructor call-sites) don't
     * have to change. Empty/absent fields are simply omitted from the
     * wire response.
     */
    public record EntryMetadata(String title, String iconUrl, Map<String, Object> meta) {
        public EntryMetadata {
            title = title == null ? "" : title;
            iconUrl = iconUrl == null ? "" : iconUrl;
            meta = meta == null ? Map.of() : Map.copyOf(meta);
        }
        public boolean isEmpty() {
            return title.isEmpty() && iconUrl.isEmpty() && meta.isEmpty();
        }
    }

    /**
     * Tool behavior hints (MCP 2025-03-26+ {@code ToolAnnotations}). A
     * {@code null} hint is left out of the wire object, so clients apply the
     * spec default for it.
     */
    public record ToolAnnotations(Boolean readOnlyHint, Boolean destructiveHint,
                                  Boolean idempotentHint, Boolean openWorldHint) {

        /** The hints as the wire {@code annotations} object; empty when all unset. */
        public Map<String, Object> toWire() {
            var out = new LinkedHashMap<String, Object>();
            if (readOnlyHint != null) {
                out.put("readOnlyHint", readOnlyHint);
            }
            if (destructiveHint != null) {
                out.put("destructiveHint", destructiveHint);
            }
            if (idempotentHint != null) {
                out.put("idempotentHint", idempotentHint);
            }
            if (openWorldHint != null) {
                out.put("openWorldHint", openWorldHint);
            }
            return out;
        }

        static ToolAnnotations of(McpTool a) {
            // Spec defaults: readOnly=false, destructive=true, idempotent=false,
            // openWorld=true. Only a tool that departs from them says anything.
            if (!a.readOnlyHint() && a.destructiveHint() && !a.idempotentHint()
                    && a.openWorldHint()) {
                return null;
            }
            return new ToolAnnotations(a.readOnlyHint(), a.destructiveHint(),
                    a.idempotentHint(), a.openWorldHint());
        }
    }

    /**
     * Supplies {@code completion/complete} candidates for one argument.
     * {@code context} holds the other arguments the client already resolved.
     */
    @FunctionalInterface
    public interface CompletionHandler {
        List<String> complete(String value, Map<String, String> context) throws Exception;
    }

    /** Wire {@code ref.type} for a prompt completion reference. */
    public static final String REF_PROMPT = "ref/prompt";
    /** Wire {@code ref.type} for a resource-template completion reference. */
    public static final String REF_RESOURCE = "ref/resource";
    /** Spec cap on the number of values in one {@code completion/complete} reply. */
    public static final int MAX_COMPLETION_VALUES = 100;

    /** The outcome of a completion lookup: capped values plus the uncapped total. */
    public record Completion(List<String> values, int total, boolean hasMore) {}

    /** A registered resource matched to a concrete URI, with its bound template variables. */
    public record ResolvedResource(ResourceEntry entry, Map<String, String> variables) {}

    private record CompletionKey(String refType, String ref, String argument) {}

    private record UriTemplate(Pattern pattern, List<String> variables) {}

    private static final Pattern TEMPLATE_VARIABLE = Pattern.compile("\\{([A-Za-z0-9_.]+)}");

    private final Map<String, ToolEntry> tools = new ConcurrentHashMap<>();
    private final Map<String, ResourceEntry> resources = new ConcurrentHashMap<>();
    private final Map<String, PromptEntry> prompts = new ConcurrentHashMap<>();
    private final Map<String, EntryMetadata> toolMetadata = new ConcurrentHashMap<>();
    private final Map<String, EntryMetadata> resourceMetadata = new ConcurrentHashMap<>();
    private final Map<String, EntryMetadata> promptMetadata = new ConcurrentHashMap<>();
    // Tools flagged @McpTool(longRunning=true) — the server-side trigger to
    // materialize a Task on the stateless 2026-07-28 transport (SEP-2663).
    // Kept as a sidecar set so the ToolEntry record signature is unchanged.
    private final Set<String> longRunningTools = ConcurrentHashMap.newKeySet();
    // Tools flagged @McpTool(uiResource=...) — MCP App tools (SEP-1865) that
    // declare a ui:// UI resource. Sidecar set, like longRunningTools.
    private final Set<String> appTools = ConcurrentHashMap.newKeySet();
    private final Map<String, ToolAnnotations> toolAnnotations = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> toolOutputSchemas = new ConcurrentHashMap<>();
    private final Map<String, UriTemplate> uriTemplates = new ConcurrentHashMap<>();
    private final Map<CompletionKey, CompletionHandler> completions = new ConcurrentHashMap<>();

    /**
     * Scan the given instance for @McpTool, @McpResource, @McpPrompt methods.
     */
    public void scan(Object instance) {
        for (var method : instance.getClass().getMethods()) {
            if (method.isAnnotationPresent(McpTool.class)) {
                var a = method.getAnnotation(McpTool.class);
                var params = extractParams(method);
                tools.put(a.name(), new ToolEntry(a.name(), a.description(), method, instance, params));
                // An MCP App tool (SEP-1865) carries _meta.ui.resourceUri so a host
                // knows which ui:// resource to render in its sandboxed iframe.
                Map<String, Object> metaMap = Map.of();
                if (!a.uiResource().isEmpty()) {
                    metaMap = Map.of(org.atmosphere.mcp.protocol.Mcp2026.META_UI,
                            Map.of(org.atmosphere.mcp.protocol.Mcp2026.META_UI_RESOURCE_URI, a.uiResource()));
                    appTools.add(a.name());
                }
                var meta = new EntryMetadata(a.title(), a.iconUrl(), metaMap);
                if (!meta.isEmpty()) {
                    toolMetadata.put(a.name(), meta);
                }
                if (a.longRunning()) {
                    longRunningTools.add(a.name());
                }
                var hints = ToolAnnotations.of(a);
                if (hints != null) {
                    toolAnnotations.put(a.name(), hints);
                }
                if (a.outputType() != void.class && a.outputType() != Void.class) {
                    toolOutputSchemas.put(a.name(), outputSchema(a.outputType()));
                }
            }
            if (method.isAnnotationPresent(McpResource.class)) {
                var a = method.getAnnotation(McpResource.class);
                var params = extractParams(method);
                resources.put(a.uri(), new ResourceEntry(a.uri(), a.name(), a.description(),
                        a.mimeType(), method, instance, params));
                indexTemplate(a.uri());
                var meta = new EntryMetadata(a.title(), a.iconUrl(), Map.of());
                if (!meta.isEmpty()) {
                    resourceMetadata.put(a.uri(), meta);
                }
            }
            if (method.isAnnotationPresent(McpPrompt.class)) {
                var a = method.getAnnotation(McpPrompt.class);
                var params = extractParams(method);
                prompts.put(a.name(), new PromptEntry(a.name(), a.description(), method, instance, params));
                var meta = new EntryMetadata(a.title(), a.iconUrl(), Map.of());
                if (!meta.isEmpty()) {
                    promptMetadata.put(a.name(), meta);
                }
            }
        }
        // Completion methods are bound after the scan so they may reference a
        // prompt or template declared later in the same class.
        for (var method : instance.getClass().getMethods()) {
            if (method.isAnnotationPresent(McpComplete.class)) {
                registerCompletionMethod(method.getAnnotation(McpComplete.class), method, instance);
            }
        }
    }

    private void registerCompletionMethod(McpComplete a, Method method, Object instance) {
        var hasPrompt = !a.prompt().isEmpty();
        var hasResource = !a.resource().isEmpty();
        if (hasPrompt == hasResource) {
            throw new IllegalArgumentException("@McpComplete on " + method
                    + " must set exactly one of prompt or resource");
        }
        var types = method.getParameterTypes();
        var withContext = types.length == 2 && types[0] == String.class
                && Map.class.isAssignableFrom(types[1]);
        if (!(types.length == 1 && types[0] == String.class) && !withContext) {
            throw new IllegalArgumentException("@McpComplete method " + method
                    + " must take (String) or (String, Map<String, String>)");
        }
        if (!Collection.class.isAssignableFrom(method.getReturnType())) {
            throw new IllegalArgumentException("@McpComplete method " + method
                    + " must return a Collection of String values");
        }
        CompletionHandler handler = (value, context) -> {
            try {
                var raw = withContext
                        ? method.invoke(instance, value, context)
                        : method.invoke(instance, value);
                var out = new ArrayList<String>();
                if (raw instanceof Collection<?> values) {
                    for (var v : values) {
                        if (v != null) {
                            out.add(String.valueOf(v));
                        }
                    }
                }
                return out;
            } catch (InvocationTargetException e) {
                if (e.getCause() instanceof Exception cause) {
                    throw cause;
                }
                throw e;
            }
        };
        if (hasPrompt) {
            registerPromptCompletion(a.prompt(), a.argument(), handler);
        } else {
            registerResourceCompletion(a.resource(), a.argument(), handler);
        }
    }

    // ── Programmatic Tool Registration ───────────────────────────────────

    /**
     * Register a tool with a lambda handler.
     *
     * @param name        tool name (unique identifier)
     * @param description human-readable description for AI agents
     * @param params      parameter metadata (name, description, required, type)
     * @param handler     function that receives arguments and returns the result
     */
    public void registerTool(String name, String description, List<ParamEntry> params,
                             ToolHandler handler) {
        tools.put(name, new ToolEntry(name, description, params, handler));
    }

    /**
     * Register a tool with no parameters.
     */
    public void registerTool(String name, String description, ToolHandler handler) {
        registerTool(name, description, List.of(), handler);
    }

    /**
     * Remove a previously registered tool.
     *
     * @return true if the tool was found and removed
     */
    public boolean removeTool(String name) {
        longRunningTools.remove(name);
        appTools.remove(name);
        toolAnnotations.remove(name);
        toolOutputSchemas.remove(name);
        return tools.remove(name) != null;
    }

    /**
     * Mark a programmatically-registered tool as long-running (the equivalent
     * of {@code @McpTool(longRunning=true)} for lambda-registered tools).
     */
    public void markLongRunning(String name) {
        longRunningTools.add(name);
    }

    /**
     * Whether {@code name} is a long-running tool — the server-side trigger to
     * materialize a Task on the stateless transport (SEP-2663).
     */
    public boolean isLongRunning(String name) {
        return longRunningTools.contains(name);
    }

    /** Whether any long-running tool is registered (gates Tasks-extension advertisement). */
    public boolean hasLongRunningTools() {
        return !longRunningTools.isEmpty();
    }

    /** Mark a programmatically-registered tool as an MCP App tool (SEP-1865). */
    public void markAppTool(String name) {
        appTools.add(name);
    }

    /** Whether any MCP App tool is registered (gates Apps-extension advertisement). */
    public boolean hasAppTools() {
        return !appTools.isEmpty();
    }

    /**
     * Attach behavior hints to a tool (the programmatic equivalent of the
     * {@code @McpTool} hint attributes). {@code null} removes them.
     */
    public void setToolAnnotations(String name, ToolAnnotations annotations) {
        if (annotations == null || annotations.toWire().isEmpty()) {
            toolAnnotations.remove(name);
        } else {
            toolAnnotations.put(name, annotations);
        }
    }

    /** Behavior hints declared for a tool, if any. */
    public Optional<ToolAnnotations> toolAnnotations(String name) {
        return Optional.ofNullable(toolAnnotations.get(name));
    }

    /**
     * Declare a tool's {@code outputSchema} (the programmatic equivalent of
     * {@code @McpTool(outputType = ...)}). The root must be {@code type: object}
     * per the spec. {@code null} removes it.
     */
    public void setToolOutputSchema(String name, Map<String, Object> schema) {
        if (schema == null) {
            toolOutputSchemas.remove(name);
            return;
        }
        if (!"object".equals(schema.get("type"))) {
            throw new IllegalArgumentException(
                    "outputSchema for tool '" + name + "' must have type \"object\"");
        }
        toolOutputSchemas.put(name, Map.copyOf(schema));
    }

    /** The {@code outputSchema} declared for a tool, if any. */
    public Optional<Map<String, Object>> toolOutputSchema(String name) {
        return Optional.ofNullable(toolOutputSchemas.get(name));
    }

    /**
     * JSON Schema 2020-12 {@code outputSchema} for a structured result type. A
     * record maps to an object whose components are required properties; any
     * other type maps to a bare {@code {"type":"object"}} (the spec requires an
     * object root, so arrays and scalars cannot be declared as output types).
     */
    public static Map<String, Object> outputSchema(Class<?> type) {
        if (type.isArray() || Collection.class.isAssignableFrom(type) || type.isPrimitive()
                || type == String.class || Number.class.isAssignableFrom(type)
                || type == Boolean.class || type.isEnum()) {
            throw new IllegalArgumentException("outputType " + type.getName()
                    + " is not an object type; MCP outputSchema must describe a JSON object");
        }
        var schema = new LinkedHashMap<String, Object>();
        schema.put("$schema", JSON_SCHEMA_DIALECT);
        schema.put("type", "object");
        if (type.isRecord()) {
            var facets = schemaFacets(type, type, 0);
            if (facets.get("properties") != null) {
                schema.put("properties", facets.get("properties"));
                schema.put("required", facets.get("required"));
            }
        }
        return schema;
    }

    // ── Completions (completion/complete) ────────────────────────────────

    /** Register completions for one argument of a prompt. */
    public void registerPromptCompletion(String prompt, String argument, CompletionHandler handler) {
        completions.put(new CompletionKey(REF_PROMPT, prompt, argument), handler);
    }

    /** Register completions for one variable of a resource template. */
    public void registerResourceCompletion(String uriTemplate, String argument,
                                           CompletionHandler handler) {
        completions.put(new CompletionKey(REF_RESOURCE, uriTemplate, argument), handler);
    }

    /**
     * Whether any completion source exists — an explicit handler or an
     * enum-typed prompt/template argument. Gates advertising the
     * {@code completions} capability (Runtime Truth).
     */
    public boolean hasCompletions() {
        if (!completions.isEmpty()) {
            return true;
        }
        for (var p : prompts.values()) {
            if (p.params().stream().anyMatch(param -> param.type().isEnum())) {
                return true;
            }
        }
        for (var r : resources.values()) {
            if (uriTemplates.containsKey(r.uri())
                    && r.params().stream().anyMatch(param -> param.type().isEnum())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Resolve {@code completion/complete} for {@code ref}: an explicit handler
     * wins, otherwise an enum-typed argument completes from its constants
     * (case-insensitive prefix match); an argument with no source completes to
     * nothing. Returns empty when the reference itself does not exist.
     *
     * @throws IllegalArgumentException if {@code refType} is not a known type
     */
    public Optional<Completion> complete(String refType, String ref, String argument,
                                         String value, Map<String, String> context)
            throws Exception {
        List<ParamEntry> params;
        if (REF_PROMPT.equals(refType)) {
            var prompt = prompts.get(ref);
            if (prompt == null) {
                return Optional.empty();
            }
            params = prompt.params();
        } else if (REF_RESOURCE.equals(refType)) {
            var resource = resources.get(ref);
            if (resource == null || !uriTemplates.containsKey(ref)) {
                return Optional.empty();
            }
            params = resource.params();
        } else {
            throw new IllegalArgumentException("Unknown completion ref type: " + refType);
        }
        var partial = value == null ? "" : value;
        List<String> candidates;
        var handler = completions.get(new CompletionKey(refType, ref, argument));
        if (handler != null) {
            var produced = handler.complete(partial, context == null ? Map.of() : context);
            candidates = produced == null ? List.of() : produced;
        } else {
            candidates = params.stream()
                    .filter(p -> p.name().equals(argument) && p.type().isEnum())
                    .findFirst()
                    .map(p -> enumCompletions(p.type(), partial))
                    .orElse(List.of());
        }
        var total = candidates.size();
        var capped = total > MAX_COMPLETION_VALUES
                ? List.copyOf(candidates.subList(0, MAX_COMPLETION_VALUES))
                : List.copyOf(candidates);
        return Optional.of(new Completion(capped, total, total > MAX_COMPLETION_VALUES));
    }

    private static List<String> enumCompletions(Class<?> enumType, String partial) {
        var prefix = partial.toLowerCase(java.util.Locale.ROOT);
        var out = new ArrayList<String>();
        for (var constant : enumType.getEnumConstants()) {
            var name = ((Enum<?>) constant).name();
            if (name.toLowerCase(java.util.Locale.ROOT).startsWith(prefix)) {
                out.add(name);
            }
        }
        return out;
    }

    // ── Programmatic Resource Registration ───────────────────────────────

    /**
     * Register a resource with a lambda handler.
     */
    public void registerResource(String uri, String name, String description,
                                 String mimeType, List<ParamEntry> params,
                                 ResourceHandler handler) {
        resources.put(uri, new ResourceEntry(uri, name, description, mimeType, params, handler));
        indexTemplate(uri);
    }

    /**
     * Register a resource with no parameters.
     */
    public void registerResource(String uri, String name, String description,
                                 String mimeType, ResourceHandler handler) {
        registerResource(uri, name, description, mimeType, List.of(), handler);
    }

    /**
     * Remove a previously registered resource.
     */
    public boolean removeResource(String uri) {
        uriTemplates.remove(uri);
        completions.keySet().removeIf(k -> REF_RESOURCE.equals(k.refType()) && k.ref().equals(uri));
        return resources.remove(uri) != null;
    }

    /** Whether {@code uri} is a URI template ({@code {name}} variables), not a concrete URI. */
    public static boolean isTemplate(String uri) {
        return uri != null && TEMPLATE_VARIABLE.matcher(uri).find();
    }

    private void indexTemplate(String uri) {
        if (!isTemplate(uri)) {
            uriTemplates.remove(uri);
            return;
        }
        var regex = new StringBuilder("^");
        var variables = new ArrayList<String>();
        Matcher m = TEMPLATE_VARIABLE.matcher(uri);
        int last = 0;
        while (m.find()) {
            regex.append(Pattern.quote(uri.substring(last, m.start())));
            // RFC 6570 simple expansion: the value is a single segment, so it
            // cannot contain a reserved '/', '?' or '#'.
            regex.append("([^/?#]+)");
            variables.add(m.group(1));
            last = m.end();
        }
        regex.append(Pattern.quote(uri.substring(last))).append('$');
        uriTemplates.put(uri, new UriTemplate(Pattern.compile(regex.toString()), List.copyOf(variables)));
    }

    /** Registered resources that are concrete URIs (listed by {@code resources/list}). */
    public Map<String, ResourceEntry> concreteResources() {
        var out = new LinkedHashMap<String, ResourceEntry>();
        resources.forEach((uri, entry) -> {
            if (!uriTemplates.containsKey(uri)) {
                out.put(uri, entry);
            }
        });
        return Collections.unmodifiableMap(out);
    }

    /** Registered resource templates (listed by {@code resources/templates/list}). */
    public Map<String, ResourceEntry> resourceTemplates() {
        var out = new LinkedHashMap<String, ResourceEntry>();
        resources.forEach((uri, entry) -> {
            if (uriTemplates.containsKey(uri)) {
                out.put(uri, entry);
            }
        });
        return Collections.unmodifiableMap(out);
    }

    /**
     * Resolve a concrete URI from {@code resources/read} or
     * {@code resources/subscribe}: an exact registration wins; otherwise the
     * first template whose pattern matches binds its variables
     * (percent-decoded). A variable whose decoded value contains a path
     * separator or {@code ..} is rejected, so a template can never be used to
     * address outside the segment it declares (Boundary Safety).
     */
    public Optional<ResolvedResource> resolveResource(String uri) {
        if (uri == null) {
            return Optional.empty();
        }
        var exact = resources.get(uri);
        if (exact != null && !uriTemplates.containsKey(uri)) {
            return Optional.of(new ResolvedResource(exact, Map.of()));
        }
        for (var e : uriTemplates.entrySet()) {
            var m = e.getValue().pattern().matcher(uri);
            if (!m.matches()) {
                continue;
            }
            var entry = resources.get(e.getKey());
            if (entry == null) {
                continue;
            }
            var vars = new LinkedHashMap<String, String>();
            for (int i = 0; i < e.getValue().variables().size(); i++) {
                var decoded = percentDecode(m.group(i + 1));
                if (decoded == null || decoded.isEmpty() || decoded.contains("/")
                        || decoded.contains("\\") || decoded.equals("..")
                        || decoded.equals(".")) {
                    return Optional.empty();
                }
                vars.put(e.getValue().variables().get(i), decoded);
            }
            return Optional.of(new ResolvedResource(entry, Map.copyOf(vars)));
        }
        return Optional.empty();
    }

    private static String percentDecode(String raw) {
        try {
            // URLDecoder is form-decoding: protect a literal '+' so it is not
            // turned into a space (URI templates percent-encode spaces).
            return URLDecoder.decode(raw.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            logger.debug("Malformed percent-encoding in resource URI segment '{}'", raw, e);
            return null;
        }
    }

    // ── Programmatic Prompt Registration ─────────────────────────────────

    /**
     * Register a prompt with a lambda handler.
     */
    public void registerPrompt(String name, String description, List<ParamEntry> params,
                               PromptHandler handler) {
        prompts.put(name, new PromptEntry(name, description, params, handler));
    }

    /**
     * Register a prompt with no parameters.
     */
    public void registerPrompt(String name, String description, PromptHandler handler) {
        registerPrompt(name, description, List.of(), handler);
    }

    /**
     * Remove a previously registered prompt.
     */
    public boolean removePrompt(String name) {
        completions.keySet().removeIf(k -> REF_PROMPT.equals(k.refType()) && k.ref().equals(name));
        return prompts.remove(name) != null;
    }

    // ── Queries ──────────────────────────────────────────────────────────

    public Map<String, ToolEntry> tools() {
        return Collections.unmodifiableMap(tools);
    }

    /**
     * Spec-extension metadata for a registered tool, if any.
     * @see EntryMetadata
     */
    public Optional<EntryMetadata> toolMetadata(String name) {
        return Optional.ofNullable(toolMetadata.get(name));
    }

    public Optional<EntryMetadata> resourceMetadata(String uri) {
        return Optional.ofNullable(resourceMetadata.get(uri));
    }

    public Optional<EntryMetadata> promptMetadata(String name) {
        return Optional.ofNullable(promptMetadata.get(name));
    }

    /**
     * Programmatically attach spec-extension metadata to a registered tool.
     * Useful when registering tools via {@link #registerTool} (which doesn't
     * carry annotation defaults). Pass {@code null} or an empty
     * {@link EntryMetadata} to remove.
     */
    public void setToolMetadata(String name, EntryMetadata metadata) {
        if (metadata == null || metadata.isEmpty()) {
            toolMetadata.remove(name);
        } else {
            toolMetadata.put(name, metadata);
        }
    }

    public void setResourceMetadata(String uri, EntryMetadata metadata) {
        if (metadata == null || metadata.isEmpty()) {
            resourceMetadata.remove(uri);
        } else {
            resourceMetadata.put(uri, metadata);
        }
    }

    public void setPromptMetadata(String name, EntryMetadata metadata) {
        if (metadata == null || metadata.isEmpty()) {
            promptMetadata.remove(name);
        } else {
            promptMetadata.put(name, metadata);
        }
    }

    public Map<String, ResourceEntry> resources() {
        return Collections.unmodifiableMap(resources);
    }

    public Map<String, PromptEntry> prompts() {
        return Collections.unmodifiableMap(prompts);
    }

    public Optional<ToolEntry> tool(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public Optional<ResourceEntry> resource(String uri) {
        return Optional.ofNullable(resources.get(uri));
    }

    public Optional<PromptEntry> prompt(String name) {
        return Optional.ofNullable(prompts.get(name));
    }

    /**
     * The JSON Schema dialect declared on generated tool input schemas
     * (SEP-2106). {@code 2025-11-25} already defaults to 2020-12 and the
     * {@code 2026-07-28} RC formalizes it, so we declare it explicitly.
     */
    public static final String JSON_SCHEMA_DIALECT = "https://json-schema.org/draft/2020-12/schema";

    /**
     * Generate the JSON Schema 2020-12 input schema for a tool's parameters
     * (SEP-2106). The root keeps {@code type: "object"} (tool arguments are
     * always an object) and declares the {@code $schema} dialect; richer
     * compositions ({@code anyOf}/{@code oneOf}/{@code $ref}) are permitted but
     * not synthesized from {@code @McpParam} metadata.
     */
    public static Map<String, Object> inputSchema(ToolEntry tool) {
        var properties = new LinkedHashMap<String, Object>();
        var required = new ArrayList<String>();

        for (var param : tool.params()) {
            var prop = new LinkedHashMap<String, Object>();
            prop.put("type", jsonSchemaType(param.type()));
            if (!param.description().isEmpty()) {
                prop.put("description", param.description());
            }
            // Structural facets (enum values, array items, nested object
            // properties) either derived from the Java type at scan time or
            // carried in from an upstream ToolDefinition. They override the
            // flat type when they disagree — an enum is a string with a value
            // list, not the bare "string" the class mapping produces.
            prop.putAll(param.schema());
            properties.put(param.name(), prop);
            if (param.required()) {
                required.add(param.name());
            }
        }

        var schema = new LinkedHashMap<String, Object>();
        schema.put("$schema", JSON_SCHEMA_DIALECT);
        schema.put("type", "object");
        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        return schema;
    }

    private static List<ParamEntry> extractParams(Method method) {
        var params = new ArrayList<ParamEntry>();
        for (Parameter p : method.getParameters()) {
            // Skip framework-injectable types — they're not JSON-RPC arguments
            if (isInjectableType(p.getType())) {
                continue;
            }
            var facets = schemaFacets(p.getType(), p.getParameterizedType());
            var mcpParam = p.getAnnotation(McpParam.class);
            if (mcpParam != null) {
                params.add(new ParamEntry(mcpParam.name(), mcpParam.description(),
                        mcpParam.required(), p.getType(), facets));
            } else {
                // Fall back to parameter name if -parameters compiler flag is used
                params.add(new ParamEntry(p.getName(), "", true, p.getType(), facets));
            }
        }
        return params;
    }

    /**
     * Returns true if the given type is a framework-injectable type that should
     * not appear in the tool's JSON Schema (it's injected by the framework,
     * not supplied by the MCP client).
     */
    public static boolean isInjectableType(Class<?> type) {
        // Core Atmosphere types
        if (type == org.atmosphere.cpr.Broadcaster.class
                || type == org.atmosphere.cpr.AtmosphereConfig.class
                || type == org.atmosphere.cpr.BroadcasterFactory.class
                || type == org.atmosphere.cpr.AtmosphereFramework.class) {
            return true;
        }
        // Multi-round-trip input context (SEP-2322) — injected per invocation.
        if (type == org.atmosphere.mcp.protocol.McpInputContext.class) {
            return true;
        }
        // Server→client request surface (sampling / roots / elicitation) —
        // injected per invocation, never asked of the model.
        if (type == org.atmosphere.mcp.runtime.McpServerContext.class) {
            return true;
        }
        // atmosphere-ai StreamingSession (optional dependency)
        try {
            var streamingSessionClass = Class.forName("org.atmosphere.ai.StreamingSession");
            if (streamingSessionClass.isAssignableFrom(type)) {
                return true;
            }
        } catch (ClassNotFoundException ex) {
            logger.trace("atmosphere-ai not on classpath", ex);
        }
        return false;
    }

    private static String jsonSchemaType(Class<?> type) {
        if (type == String.class) return "string";
        if (type == int.class || type == Integer.class) return "integer";
        if (type == long.class || type == Long.class) return "integer";
        if (type == double.class || type == Double.class) return "number";
        if (type == float.class || type == Float.class) return "number";
        if (type == boolean.class || type == Boolean.class) return "boolean";
        if (type.isEnum()) return "string";
        if (type.isArray() || java.util.Collection.class.isAssignableFrom(type)) return "array";
        return "string";
    }

    /**
     * Derive the structural JSON-Schema facets a Java parameter type implies:
     * {@code enum} for an enum class, {@code items.type} for arrays and
     * collections (element type resolved from the generic argument when
     * present), and nested {@code properties}/{@code required} for records.
     * Returns an empty map for plain scalars, which keeps their emitted schema
     * byte-identical to previous releases.
     *
     * <p>Kept local to {@code atmosphere-mcp} rather than shared with
     * {@code ToolParameter}: this module must keep working with
     * {@code atmosphere-ai} absent from the classpath.</p>
     */
    private static Map<String, Object> schemaFacets(Class<?> type,
                                                    java.lang.reflect.Type genericType) {
        return schemaFacets(type, genericType, 0);
    }

    private static Map<String, Object> schemaFacets(Class<?> type,
                                                    java.lang.reflect.Type genericType,
                                                    int depth) {
        if (type.isEnum()) {
            var names = new ArrayList<String>();
            for (var constant : type.getEnumConstants()) {
                names.add(((Enum<?>) constant).name());
            }
            return Map.of("enum", names);
        }
        if (type.isArray()) {
            return Map.of("items", Map.of("type", jsonSchemaType(type.getComponentType())));
        }
        if (java.util.Collection.class.isAssignableFrom(type)) {
            var element = collectionElement(genericType);
            return element == null ? Map.of()
                    : Map.of("items", Map.of("type", jsonSchemaType(element)));
        }
        if (type.isRecord() && depth < 3) {
            var properties = new LinkedHashMap<String, Object>();
            var required = new ArrayList<String>();
            for (var component : type.getRecordComponents()) {
                var prop = new LinkedHashMap<String, Object>();
                prop.put("type", jsonSchemaType(component.getType()));
                prop.putAll(schemaFacets(component.getType(), component.getGenericType(), depth + 1));
                properties.put(component.getName(), prop);
                required.add(component.getName());
            }
            if (properties.isEmpty()) {
                return Map.of();
            }
            return Map.of("type", "object", "properties", properties, "required", required);
        }
        return Map.of();
    }

    /** Element class of a {@code Collection<E>}; {@code null} when unresolvable. */
    private static Class<?> collectionElement(java.lang.reflect.Type genericType) {
        if (genericType instanceof java.lang.reflect.ParameterizedType parameterized) {
            var args = parameterized.getActualTypeArguments();
            if (args.length == 1 && args[0] instanceof Class<?> element) {
                return element;
            }
        }
        return null;
    }
}
