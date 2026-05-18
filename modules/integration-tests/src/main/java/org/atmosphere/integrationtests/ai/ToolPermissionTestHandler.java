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
package org.atmosphere.integrationtests.ai;

import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordingFile;
import org.atmosphere.ai.StreamingSessions;
import org.atmosphere.ai.approval.ToolApprovalPolicy;
import org.atmosphere.ai.jfr.ToolInvocationEvent;
import org.atmosphere.ai.tool.PropertiesToolPermissionPolicy;
import org.atmosphere.ai.tool.ToolDefinition;
import org.atmosphere.ai.tool.ToolExecutionHelper;
import org.atmosphere.ai.tool.ToolPermissionPolicy;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.RawMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Drives {@link ToolExecutionHelper#executeWithApproval} under a per-tool
 * permission policy and exposes the wire-level result on the websocket so
 * the spec can assert the DENY tri-state actually short-circuits the
 * executor and emits a JFR {@code ToolInvocation} with outcome
 * {@code DENIED}.
 *
 * <p>Prompt routing:</p>
 * <ul>
 *   <li>{@code allow}   — ALLOW decision; underlying tool executor runs and
 *       returns {@code "ok"}.</li>
 *   <li>{@code deny}    — DENY decision; executor stays at zero invocations,
 *       a cancellation JSON is sent back, and a JFR ToolInvocation/DENIED
 *       event fires.</li>
 *   <li>anything else   — same as {@code allow}.</li>
 * </ul>
 */
public class ToolPermissionTestHandler implements AtmosphereHandler {

    private static final Logger logger = LoggerFactory.getLogger(ToolPermissionTestHandler.class);

    @Override
    public void onRequest(AtmosphereResource resource) throws IOException {
        resource.suspend();
        var prompt = resource.getRequest().getReader().readLine();
        if (prompt == null || prompt.isBlank()) {
            return;
        }
        Thread.ofVirtual().name("tool-permission-test").start(() -> handlePrompt(resource, prompt.trim()));
    }

    private void handlePrompt(AtmosphereResource resource, String mode) {
        var session = StreamingSessions.start(resource);
        var previousPolicy = ToolPermissionPolicy.global();
        Path dump = null;
        try {
            var props = new Properties();
            props.setProperty("atmosphere.tools.permissions.default", "allow");
            props.setProperty("atmosphere.tools.permissions.dangerous",
                    "deny".equalsIgnoreCase(mode) ? "deny" : "allow");
            ToolPermissionPolicy.setGlobal(PropertiesToolPermissionPolicy.from(props));

            var executor = new AtomicInteger();
            var tool = ToolDefinition.builder("dangerous", "deny-or-allow probe")
                    .executor(args -> {
                        executor.incrementAndGet();
                        return "ok";
                    })
                    .build();

            try (var recording = new Recording()) {
                recording.enable("org.atmosphere.ai.ToolInvocation");
                recording.start();

                var result = ToolExecutionHelper.executeWithApproval(
                        "dangerous", tool, Map.of(), null, null,
                        ToolApprovalPolicy.annotated());

                recording.stop();
                dump = Files.createTempFile("atmosphere-perm-e2e-", ".jfr");
                recording.dump(dump);

                session.sendMetadata("ai.tool.permission.mode", mode);
                session.sendMetadata("ai.tool.permission.executor.calls", executor.get());
                session.sendMetadata("ai.tool.permission.result", result);
                emitDeniedCount(session, dump);
            }
            session.complete();
        } catch (Exception e) {
            logger.error("ToolPermission e2e handler failed", e);
            session.error(e);
        } finally {
            ToolPermissionPolicy.setGlobal(previousPolicy);
            if (dump != null) {
                try {
                    Files.deleteIfExists(dump);
                } catch (IOException ignored) {
                    // best-effort cleanup
                }
            }
        }
    }

    private static void emitDeniedCount(org.atmosphere.ai.StreamingSession session,
                                        Path dump) throws IOException {
        int denied = 0;
        try (var file = new RecordingFile(dump)) {
            while (file.hasMoreEvents()) {
                var event = file.readEvent();
                if (!"org.atmosphere.ai.ToolInvocation".equals(event.getEventType().getName())) {
                    continue;
                }
                if (ToolInvocationEvent.OUTCOME_DENIED.equals(event.getValue("outcome"))) {
                    denied++;
                }
            }
        }
        session.sendMetadata("ai.tool.permission.jfr.denied", denied);
    }

    @Override
    public void onStateChange(AtmosphereResourceEvent event) throws IOException {
        if (event.isCancelled() || event.isResumedOnTimeout()
                || event.isClosedByClient() || event.isClosedByApplication()) {
            return;
        }
        var message = event.getMessage();
        if (message instanceof RawMessage raw && raw.message() instanceof String json) {
            event.getResource().getResponse().write(json);
            event.getResource().getResponse().flushBuffer();
        }
    }

    @Override
    public void destroy() {
    }
}
