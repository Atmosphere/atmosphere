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

import io.quarkus.test.QuarkusExtensionTest;
import io.quarkus.test.common.http.TestHTTPResource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Counterpart to {@link AtmosphereConsoleInfoMetricsFlagTest}: without a
 * Prometheus-backed registry there is no export route, so
 * {@code /api/console/info} must report {@code hasMetrics: false} and omit
 * {@code metricsPath} entirely — the console never learns a URL that would
 * 404 (Runtime Truth — Invariant #5). The shared test classpath carries
 * {@code quarkus-micrometer} (core registry, no exporter), which is exactly
 * the honest-negative scenario: meters exist, no HTTP metrics plane does.
 */
public class AtmosphereConsoleInfoMetricsAbsentTest {

    @RegisterExtension
    static final QuarkusExtensionTest unitTest = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClass(AtmosphereConsoleInfoMetricsAbsentTest.class))
            .overrideConfigKey("quarkus.atmosphere.packages",
                    "org.atmosphere.quarkus.deployment")
            .overrideConfigKey("quarkus.http.test-port", "0");

    @TestHTTPResource("/api/console/info")
    URL infoUrl;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    public void infoReportsNoMetricsPlane() throws Exception {
        var response = http.send(
                HttpRequest.newBuilder(URI.create(infoUrl.toString())).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "/api/console/info must respond");
        assertTrue(response.body().contains("\"hasMetrics\":false"),
                "hasMetrics must be false without a Prometheus export route: "
                        + response.body());
        assertFalse(response.body().contains("metricsPath"),
                "no metricsPath may be advertised when the route does not exist: "
                        + response.body());
    }
}
