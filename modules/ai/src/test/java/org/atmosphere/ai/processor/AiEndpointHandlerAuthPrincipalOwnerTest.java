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
package org.atmosphere.ai.processor;

import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereRequestImpl;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.FrameworkConfig;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.security.Principal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * A run's owner must follow the identity {@code AuthInterceptor} resolved.
 *
 * <p>{@code AuthInterceptor} validates the connection's token and publishes the
 * principal as {@link FrameworkConfig#AUTH_PRINCIPAL}. It sets neither
 * {@code ai.userId} nor the servlet {@code getUserPrincipal()}, so before 4.0.70
 * {@code resolveRunOwner} saw no identity at all and returned {@code "anonymous"}
 * for every authenticated caller — collapsing every user of the framework's own
 * token auth into one bucket. Per-user long-term memory keys on exactly this
 * value, so user B was served user A's facts (found by the 2026-08-31 sample
 * sweep on {@code spring-boot-personal-assistant}).</p>
 */
class AiEndpointHandlerAuthPrincipalOwnerTest {

    private static AtmosphereResource resourceFor(AtmosphereRequest request) {
        return (AtmosphereResource) Proxy.newProxyInstance(
                AtmosphereResource.class.getClassLoader(),
                new Class<?>[] {AtmosphereResource.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getRequest" -> request;
                    case "uuid" -> "test-resource";
                    case "toString" -> "resourceFor(test)";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> method.getReturnType() == boolean.class ? Boolean.FALSE : null;
                });
    }

    private static Principal named(String name) {
        return () -> name;
    }

    @Test
    void ownerFollowsTheAuthInterceptorPrincipal() {
        AtmosphereRequest request = new AtmosphereRequestImpl.Builder().build();
        request.setAttribute(FrameworkConfig.AUTH_PRINCIPAL, named("alice"));

        assertEquals("alice", AiEndpointHandler.resolveRunOwner(resourceFor(request)),
                "an authenticated caller must own their run; 'anonymous' here is the bug that "
                        + "pooled every token-authenticated user into one identity");
        assertEquals("alice", request.getAttribute("ai.userId"),
                "ai.userId must be published too — long-term memory keys on it");
    }

    @Test
    void twoAuthenticatedCallersDoNotShareAnOwner() {
        AtmosphereRequest alice = new AtmosphereRequestImpl.Builder().build();
        alice.setAttribute(FrameworkConfig.AUTH_PRINCIPAL, named("alice"));
        AtmosphereRequest bob = new AtmosphereRequestImpl.Builder().build();
        bob.setAttribute(FrameworkConfig.AUTH_PRINCIPAL, named("bob"));

        assertNotEquals(AiEndpointHandler.resolveRunOwner(resourceFor(alice)),
                AiEndpointHandler.resolveRunOwner(resourceFor(bob)),
                "distinct principals must not collapse onto one owner — that is the "
                        + "cross-user memory leak this fix closes");
    }

    @Test
    void anExplicitUserIdStillWins() {
        AtmosphereRequest request = new AtmosphereRequestImpl.Builder().build();
        request.setAttribute("ai.userId", "app-chosen");
        request.setAttribute(FrameworkConfig.AUTH_PRINCIPAL, named("alice"));

        assertEquals("app-chosen", AiEndpointHandler.resolveRunOwner(resourceFor(request)),
                "an app that resolves identity itself keeps precedence over the interceptor");
    }

    @Test
    void anonymousStaysAnonymousWhenNothingAuthenticated() {
        AtmosphereRequest request = new AtmosphereRequestImpl.Builder().build();

        assertEquals("anonymous", AiEndpointHandler.resolveRunOwner(resourceFor(request)),
                "with no identity anywhere the owner must stay anonymous, not invent one");
    }
}
