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
package org.atmosphere.samples.springboot.personalassistant;

import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereRequestImpl;
import org.atmosphere.cpr.AtmosphereResource;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The primary assistant must give every connection a stable {@code ai.userId}.
 *
 * <p>{@code LongTermMemoryInterceptor} short-circuits BOTH recall and
 * on-disconnect extraction when the identity is blank, so without this stamp the
 * sample's headline cross-session recall is silently dead on
 * {@code /atmosphere/agent/primary-assistant} — the endpoint the README, the
 * sweep matrix and the Console all drive. {@link LongTermMemoryConsumerTest}
 * proves the interceptor recalls and isolates correctly once it HAS an identity;
 * this test proves the identity actually arrives.</p>
 *
 * <p>The end-to-end proof lives in {@code e2e/tests/personal-assistant.spec.ts},
 * which needs a real model and skips against the demo runtime. These assertions
 * need neither, so they run on every CI build.</p>
 */
class PrimaryAssistantUserIdTest {

    private static final String ATTRIBUTE = "ai.userId";

    /**
     * Minimal {@link AtmosphereResource} standing in for a live connection: only
     * {@code getRequest()} is reached from the {@code @Ready} callback, and the
     * resource type is sealed-adjacent enough that a dynamic proxy is cheaper
     * (and more honest) than mocking it.
     */
    private static AtmosphereResource resourceFor(AtmosphereRequest request) {
        return (AtmosphereResource) Proxy.newProxyInstance(
                AtmosphereResource.class.getClassLoader(),
                new Class<?>[] {AtmosphereResource.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getRequest" -> request;
                    case "toString" -> "resourceFor(" + request + ")";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> {
                        var returnType = method.getReturnType();
                        yield returnType == boolean.class ? Boolean.FALSE : null;
                    }
                });
    }

    private static AtmosphereRequest requestWithUser(String user) {
        var builder = new AtmosphereRequestImpl.Builder().pathInfo("/atmosphere/agent/primary-assistant");
        if (user != null) {
            builder.queryStrings(Map.of("user", new String[] {user}));
        }
        return builder.build();
    }

    @Test
    void stampsTheUserQueryParameterAsTheMemoryIdentity() {
        var request = requestWithUser("sweep-alice");

        new PrimaryAssistant().onReady(resourceFor(request));

        assertEquals("sweep-alice", request.getAttribute(ATTRIBUTE),
                "?user= must become ai.userId — two tabs with the same value share long-term "
                        + "memory, and LongTermMemoryInterceptor does nothing at all without it");
    }

    @Test
    void fallsBackToTheDemoIdentityWhenNoUserIsSupplied() {
        var request = requestWithUser(null);

        new PrimaryAssistant().onReady(resourceFor(request));

        assertEquals("demo-user", request.getAttribute(ATTRIBUTE),
                "an anonymous connection still needs a non-blank identity; a blank one makes the "
                        + "interceptor skip both recall and extraction, which reads as 'memory is broken'");
    }

    @Test
    void treatsABlankUserParameterAsAbsent() {
        var request = requestWithUser("   ");

        new PrimaryAssistant().onReady(resourceFor(request));

        assertEquals("demo-user", request.getAttribute(ATTRIBUTE),
                "?user= with only whitespace must not become the memory key — a blank identity "
                        + "disables long-term memory outright");
    }

    @Test
    void neverOverwritesAnIdentityResolvedUpstream() {
        // An authenticated deployment stamps the real principal before @Ready runs
        // (InteractionsAutoConfiguration / InteractionsDemoPrincipalInterceptor both
        // set the attribute only when absent). Clobbering it here would collapse every
        // logged-in user's memory onto one shared bucket.
        var request = requestWithUser("sweep-alice");
        request.setAttribute(ATTRIBUTE, "authenticated-principal");

        new PrimaryAssistant().onReady(resourceFor(request));

        assertEquals("authenticated-principal", request.getAttribute(ATTRIBUTE),
                "a demo default must never downgrade an identity the auth stack already resolved");
    }
}
