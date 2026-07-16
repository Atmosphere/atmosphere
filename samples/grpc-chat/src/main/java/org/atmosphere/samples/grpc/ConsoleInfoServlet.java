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
package org.atmosphere.samples.grpc;

import java.io.IOException;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Bare-Jetty stand-in for the starters' {@code /api/console/info} endpoint so
 * the bundled Atmosphere Console (served from {@code webapp/atmosphere/console})
 * can configure itself. Every value is a static runtime truth of this sample:
 * the endpoint is the Connect service base the {@link ConnectProtocolServlet}
 * mounts, the mode is broadcast (multi-client chat over a shared topic), and
 * the transport selects the console's {@code grpc} Connect-JSON adapter.
 */
public class ConsoleInfoServlet extends HttpServlet {

    private static final String INFO_JSON = """
            {"subtitle":"gRPC / Connect chat — multi-client broadcast over AtmosphereService",\
            "endpoint":"/org.atmosphere.grpc.AtmosphereService",\
            "runtime":"none",\
            "mode":"broadcast",\
            "transport":"grpc",\
            "hasInteractions":false,\
            "hasVerifier":false,\
            "hasAdmin":false,\
            "hasWorkspace":false}""";

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setStatus(HttpServletResponse.SC_OK);
        resp.setContentType("application/json;charset=UTF-8");
        resp.setHeader("Cache-Control", "no-cache");
        resp.getWriter().write(INFO_JSON);
    }
}
