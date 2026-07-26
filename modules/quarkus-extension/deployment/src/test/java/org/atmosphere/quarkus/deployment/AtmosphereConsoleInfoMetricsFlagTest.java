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

import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import io.quarkus.maven.dependency.ArtifactDependency;
import io.quarkus.maven.dependency.Dependency;
import io.quarkus.test.QuarkusExtensionTest;
import io.quarkus.test.common.http.TestHTTPResource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves {@code /api/console/info} advertises the metrics read plane on
 * Quarkus only when the Prometheus export route is genuinely live. Boots with
 * {@code quarkus-micrometer-registry-prometheus} forced onto this test's app
 * classpath (scoped via {@code setForcedDependencies} like the other
 * observability build-step tests) and asserts {@code hasMetrics: true} with
 * the resolved {@code /q/metrics} path AND that {@code /q/metrics} itself
 * responds with {@code atmosphere_*} series — the flag and the endpoint it
 * points at are verified together (Runtime Truth — Invariant #5). The
 * no-Prometheus counterpart is {@link AtmosphereConsoleInfoMetricsAbsentTest}.
 */
public class AtmosphereConsoleInfoMetricsFlagTest {

    private static List<Dependency> prometheusDeps() {
        return List.of(new ArtifactDependency("io.quarkus",
                "quarkus-micrometer-registry-prometheus", null, "jar",
                System.getProperty("quarkus.version", "3.36.0")));
    }

    @RegisterExtension
    static final QuarkusExtensionTest unitTest = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClass(AtmosphereConsoleInfoMetricsFlagTest.class))
            .setForcedDependencies(prometheusDeps())
            .overrideConfigKey("quarkus.atmosphere.packages",
                    "org.atmosphere.quarkus.deployment")
            .overrideConfigKey("quarkus.http.test-port", "0");

    @TestHTTPResource("/api/console/info")
    URL infoUrl;

    @TestHTTPResource("/q/metrics")
    URL metricsUrl;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    public void infoAdvertisesLivePrometheusPlane() throws Exception {
        var info = get(infoUrl);
        assertEquals(200, info.statusCode(), "/api/console/info must respond");
        assertTrue(info.body().contains("\"hasMetrics\":true"),
                "hasMetrics must be true when the Prometheus registry booted: " + info.body());
        assertTrue(info.body().contains("\"metricsFormat\":\"prometheus\""),
                "the wire format must be named explicitly: " + info.body());
        assertTrue(info.body().contains("\"metricsPath\":\"/q/metrics\""),
                "the resolved export route must be reported: " + info.body());
    }

    @Test
    public void advertisedEndpointGenuinelyResponds() throws Exception {
        var metrics = get(metricsUrl);
        assertEquals(200, metrics.statusCode(),
                "the advertised /q/metrics route must be live");
        assertTrue(metrics.body().contains("atmosphere_"),
                "Prometheus export must carry atmosphere_* series: "
                        + metrics.body().lines().limit(10).toList());
    }

    /**
     * Pins the two Prometheus naming rules the console's parser resolves
     * dotted meter names through (see {@code lib/metrics.ts}
     * {@code meterFromPrometheus}): a counter whose name already ends in
     * {@code _total} is not suffixed twice, and a gauge carries no suffix at
     * all. The parser goes dotted-name → Prometheus-name because the reverse
     * is ambiguous; if Micrometer's convention ever moved, the console would
     * silently render nothing, so the contract is asserted against the real
     * exporter rather than a hand-written fixture.
     */
    @Test
    public void exportUsesTheNamingTheConsoleParserResolves() throws Exception {
        var body = get(metricsUrl).body();
        var atmosphereLines = body.lines()
                .filter(l -> l.startsWith("atmosphere_"))
                .toList();

        assertTrue(atmosphereLines.stream()
                        .anyMatch(l -> l.startsWith("atmosphere_connections_total{")
                                || "atmosphere_connections_total".equals(sampleName(l))),
                "atmosphere.connections.total is a counter already ending in _total and must "
                        + "not be suffixed twice: " + atmosphereLines);
        assertTrue(atmosphereLines.stream()
                        .anyMatch(l -> "atmosphere_connections_active".equals(sampleName(l))),
                "atmosphere.connections.active is a gauge and must carry no suffix: "
                        + atmosphereLines);
    }

    /** The sample name of an exposition line, without its label set. */
    private static String sampleName(String line) {
        var brace = line.indexOf('{');
        var space = line.indexOf(' ');
        var end = brace >= 0 ? brace : (space >= 0 ? space : line.length());
        return line.substring(0, end);
    }

    private HttpResponse<String> get(URL url) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url.toString())).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
