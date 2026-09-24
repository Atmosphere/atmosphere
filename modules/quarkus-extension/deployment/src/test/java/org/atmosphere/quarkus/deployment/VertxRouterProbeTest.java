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
package org.atmosphere.quarkus.deployment;

import io.quarkus.maven.dependency.ArtifactDependency;
import io.quarkus.maven.dependency.Dependency;
import io.quarkus.test.QuarkusExtensionTest;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.vertx.http.runtime.security.QuarkusHttpUser;
import io.vertx.ext.web.Router;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the two Quarkus router behaviours a Vert.x container mode relies on,
 * using a route mounted on the application router the same way a
 * {@code RouteBuildItem} handler is: (1) {@code quarkus.http.auth.permission}
 * rejects an unauthenticated request before the route handler runs, and an
 * authenticated one reaches it with its {@code SecurityIdentity}; (2)
 * {@code HttpServerRequest.toWebSocket()} upgrades from inside the router,
 * including on an authenticated path.
 */
public class VertxRouterProbeTest {

    private static List<Dependency> securityDeps() {
        return List.of(new ArtifactDependency("io.quarkus",
                "quarkus-elytron-security-properties-file-deployment", null, "jar", System.getProperty("quarkus.version", "3.39.5")));
    }

    @RegisterExtension
    static final QuarkusExtensionTest unitTest = new QuarkusExtensionTest()
            .setForcedDependencies(securityDeps())
            .withApplicationRoot(jar -> jar.addClasses(VertxRouterProbeTest.class, ProbeRoutes.class))
            .overrideConfigKey("quarkus.atmosphere.packages", "org.atmosphere.quarkus.deployment.none")
            .overrideConfigKey("quarkus.http.test-port", "0")
            .overrideConfigKey("quarkus.http.auth.basic", "true")
            .overrideConfigKey("quarkus.security.users.embedded.enabled", "true")
            .overrideConfigKey("quarkus.security.users.embedded.plain-text", "true")
            .overrideConfigKey("quarkus.security.users.embedded.users.alice", "secret")
            .overrideConfigKey("quarkus.http.auth.permission.probe.paths", "/probe/secure/*")
            .overrideConfigKey("quarkus.http.auth.permission.probe.policy", "authenticated");

    /** Routes registered on the application router, like a RouteBuildItem handler. */
    @ApplicationScoped
    public static class ProbeRoutes {
        static final AtomicInteger SECURE_HITS = new AtomicInteger();

        void init(@Observes Router router) {
            router.route("/probe/secure/who").handler(rc -> {
                SECURE_HITS.incrementAndGet();
                var user = (QuarkusHttpUser) rc.user();
                var name = user == null ? "<none>" : user.getSecurityIdentity().getPrincipal().getName();
                rc.response().end(name);
            });
            for (var path : List.of("/probe/ws", "/probe/secure/ws")) {
                router.route(path).handler(rc -> rc.request().toWebSocket().onSuccess(ws ->
                        ws.textMessageHandler(msg -> ws.writeTextMessage("echo:" + msg)))
                        .onFailure(rc::fail));
            }
        }
    }

    @TestHTTPResource("/probe/secure/who")
    URL secureUrl;

    private final HttpClient http = HttpClient.newHttpClient();

    private static String basic() {
        return "Basic " + Base64.getEncoder().encodeToString("alice:secret".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void permissionRejectsBeforeTheRouteHandlerRuns() throws Exception {
        var before = ProbeRoutes.SECURE_HITS.get();
        var res = http.send(HttpRequest.newBuilder(secureUrl.toURI()).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(401, res.statusCode());
        assertEquals(before, ProbeRoutes.SECURE_HITS.get(), "the handler must not run for a rejected request");
    }

    @Test
    public void authenticatedRequestReachesTheHandlerWithItsIdentity() throws Exception {
        var res = http.send(HttpRequest.newBuilder(secureUrl.toURI()).header("Authorization", basic()).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, res.statusCode());
        assertEquals("alice", res.body());
    }

    @Test
    public void toWebSocketUpgradesInsideTheQuarkusRouter() throws Exception {
        assertEquals("echo:hi", roundTrip("/probe/ws", null));
    }

    @Test
    public void toWebSocketUpgradesOnAnAuthenticatedPath() throws Exception {
        assertEquals("echo:hi", roundTrip("/probe/secure/ws", basic()));
    }

    private String roundTrip(String path, String authorization) throws Exception {
        var uri = URI.create("ws://" + secureUrl.getHost() + ":" + secureUrl.getPort() + path);
        var received = new CompletableFuture<String>();
        var builder = http.newWebSocketBuilder();
        if (authorization != null) {
            builder.header("Authorization", authorization);
        }
        var ws = builder.buildAsync(uri, new WebSocket.Listener() {
            @Override
            public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                received.complete(data.toString());
                return null;
            }
        }).get(10, TimeUnit.SECONDS);
        ws.sendText("hi", true);
        var reply = received.get(10, TimeUnit.SECONDS);
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "done");
        return reply;
    }
}
