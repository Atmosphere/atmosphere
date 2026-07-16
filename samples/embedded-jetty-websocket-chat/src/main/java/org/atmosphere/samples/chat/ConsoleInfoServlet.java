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
package org.atmosphere.samples.chat;

import java.io.IOException;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Embedded-Jetty stand-in for the starters' {@code /api/console/info} endpoint
 * so the bundled Atmosphere Console (shipped under {@code webapp/atmosphere/console},
 * synced by {@code scripts/sync-console-bundle.sh}) can configure itself:
 * broadcast dialect over the {@code @WebSocketHandlerService} chat endpoint via
 * the default Atmosphere WebSocket transport.
 */
public class ConsoleInfoServlet extends HttpServlet {

    private static final String INFO_JSON = """
            {"subtitle":"WebSocket broadcast chat on embedded Jetty",\
            "endpoint":"/chat",\
            "runtime":"none",\
            "mode":"broadcast",\
            "transport":"atmosphere",\
            "hasInteractions":false,\
            "hasVerifier":false}""";

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setStatus(HttpServletResponse.SC_OK);
        resp.setContentType("application/json;charset=UTF-8");
        resp.setHeader("Cache-Control", "no-cache");
        resp.getWriter().write(INFO_JSON);
    }
}
