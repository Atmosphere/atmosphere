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
package org.atmosphere.spring.boot;

import org.atmosphere.ai.CollectingSession;
import org.atmosphere.interactions.Interaction;
import org.atmosphere.interactions.InteractionQuery;
import org.atmosphere.interactions.InteractionRequest;
import org.atmosphere.interactions.InteractionService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;

import java.security.Principal;
import java.util.Map;

/**
 * REST surface for the Interactions API — the HTTP delivery channel for the
 * runtime-agnostic {@link InteractionService}. All endpoints are under
 * {@code /api/interactions}.
 *
 * <p>Mutating operations (create, continue, cancel, delete) are default-deny per
 * Correctness Invariant #6: each passes {@link #guardWrite} which requires both
 * the {@code atmosphere.interactions.http-write-enabled} feature flag and an
 * authenticated principal. Reads resolve the caller and are ownership-scoped by
 * {@link InteractionService}, so an unauthenticated reader only ever sees
 * anonymous interactions. The principal resolution order mirrors
 * {@code AtmosphereAdminEndpoint} / {@code AiEndpointHandler}.</p>
 */
@AutoConfiguration(after = InteractionsAutoConfiguration.class)
@RestController
@RequestMapping("/api/interactions")
@ConditionalOnBean(InteractionService.class)
public class InteractionsEndpoint {

    private final InteractionService service;
    private final Environment env;

    public InteractionsEndpoint(InteractionService service, Environment env) {
        this.service = service;
        this.env = env;
    }

    /** Whether mutating HTTP operations are enabled (default-deny). Re-read per call. */
    boolean writeEnabled() {
        return Boolean.parseBoolean(
                env.getProperty("atmosphere.interactions.http-write-enabled", "false"));
    }

    /**
     * Create an interaction turn. When the body sets {@code "background": true}
     * the run is detached and the {@code RUNNING} record returns immediately;
     * otherwise the turn runs synchronously and the terminal record is returned.
     */
    @PostMapping
    public ResponseEntity<?> create(HttpServletRequest request,
                                    @RequestBody(required = false) Map<String, Object> body) {
        var denied = guardWrite(request);
        if (denied != null) {
            return denied;
        }
        var message = string(body, "message");
        if (message == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "missing 'message' field"));
        }
        var principal = resolvePrincipal(request);
        var req = buildRequest(body, message);
        var result = req.background()
                ? service.createBackground(req, principal)
                : service.create(req, new CollectingSession(), principal);
        return ResponseEntity.ok(result);
    }

    /** Continue an existing interaction with a new turn. */
    @PostMapping("/{id}/continue")
    public ResponseEntity<?> continueInteraction(HttpServletRequest request,
                                                 @PathVariable("id") String id,
                                                 @RequestBody(required = false) Map<String, Object> body) {
        var denied = guardWrite(request);
        if (denied != null) {
            return denied;
        }
        var message = string(body, "message");
        if (message == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "missing 'message' field"));
        }
        var principal = resolvePrincipal(request);
        try {
            var req = buildRequest(body, message);
            var result = req.background()
                    ? service.createBackground(req.withPrevious(id), principal)
                    : service.continueInteraction(id, req, new CollectingSession(), principal);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** Retrieve an interaction by id (ownership-scoped to the caller). */
    @GetMapping("/{id}")
    public ResponseEntity<?> get(HttpServletRequest request, @PathVariable("id") String id) {
        var principal = resolvePrincipal(request);
        try {
            return service.get(id, principal)
                    .<ResponseEntity<?>>map(ResponseEntity::ok)
                    .orElseGet(() -> ResponseEntity.notFound().build());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** List the caller's interactions, optionally narrowed to one conversation. */
    @GetMapping
    public java.util.List<Interaction> list(HttpServletRequest request,
                                            @RequestParam(value = "conversationId", required = false)
                                            String conversationId) {
        var principal = resolvePrincipal(request);
        var query = conversationId != null
                ? InteractionQuery.forConversation(conversationId)
                : InteractionQuery.forUser(principal);
        return service.list(query, principal);
    }

    /** Cancel an in-flight background interaction the caller owns. */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<?> cancel(HttpServletRequest request, @PathVariable("id") String id) {
        var denied = guardWrite(request);
        if (denied != null) {
            return denied;
        }
        try {
            return service.cancel(id, resolvePrincipal(request))
                    ? ResponseEntity.ok(Map.of("status", "cancel requested"))
                    : ResponseEntity.notFound().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** Delete an interaction the caller owns. */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(HttpServletRequest request, @PathVariable("id") String id) {
        var denied = guardWrite(request);
        if (denied != null) {
            return denied;
        }
        try {
            return service.delete(id, resolvePrincipal(request))
                    ? ResponseEntity.ok(Map.of("deleted", id))
                    : ResponseEntity.notFound().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private ResponseEntity<Map<String, Object>> guardWrite(HttpServletRequest request) {
        if (!writeEnabled()) {
            return ResponseEntity.status(403).body(Map.of(
                    "error", "Interaction write operations disabled",
                    "hint", "Set atmosphere.interactions.http-write-enabled=true to enable"));
        }
        if (resolvePrincipalOrNull(request) == null) {
            return ResponseEntity.status(401).body(Map.of(
                    "error", "Authentication required for interaction write operations"));
        }
        return null;
    }

    private InteractionRequest buildRequest(Map<String, Object> body, String message) {
        return new InteractionRequest(
                string(body, "previousInteractionId"),
                message,
                string(body, "agentId"),
                string(body, "model"),
                string(body, "systemPrompt"),
                java.util.List.of(),
                metadata(body),
                bool(body, "background"),
                body == null || !body.containsKey("store") || bool(body, "store"));
    }

    private static Map<String, Object> metadata(Map<String, Object> body) {
        if (body != null && body.get("metadata") instanceof Map<?, ?> m) {
            var out = new java.util.LinkedHashMap<String, Object>();
            m.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
        return Map.of();
    }

    private static String string(Map<String, Object> body, String key) {
        if (body != null && body.get(key) instanceof String s && !s.isBlank()) {
            return s;
        }
        return null;
    }

    private static boolean bool(Map<String, Object> body, String key) {
        return body != null && Boolean.TRUE.equals(body.get(key));
    }

    private static String resolvePrincipal(HttpServletRequest request) {
        var resolved = resolvePrincipalOrNull(request);
        return resolved != null ? resolved : InteractionService.ANONYMOUS;
    }

    private static String resolvePrincipalOrNull(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        var principal = request.getUserPrincipal();
        if (principal != null && principal.getName() != null && !principal.getName().isBlank()) {
            return principal.getName();
        }
        if (request.getAttribute("org.atmosphere.auth.principal") instanceof Principal attr
                && attr.getName() != null && !attr.getName().isBlank()) {
            return attr.getName();
        }
        if (request.getAttribute("ai.userId") instanceof String s && !s.isBlank()) {
            return s;
        }
        return null;
    }
}
