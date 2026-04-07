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
package org.atmosphere.admin;

import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceEventListenerAdapter;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.BroadcasterFactory;
import org.atmosphere.cpr.BroadcasterListenerAdapter;
import org.atmosphere.cpr.Deliver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

/**
 * Hooks into framework listeners and produces {@link AdminEvent} instances
 * that are broadcast to admin dashboard clients via
 * {@link AdminEventHandler}.
 *
 * <p>Registers as a {@link org.atmosphere.cpr.BroadcasterListener} to
 * capture broadcaster lifecycle and resource connect/disconnect events.</p>
 *
 * @since 4.0
 */
public final class AdminEventProducer {

    private static final Logger logger = LoggerFactory.getLogger(AdminEventProducer.class);

    private final AtmosphereFramework framework;

    public AdminEventProducer(AtmosphereFramework framework) {
        this.framework = framework;
    }

    /**
     * Install the event producer by registering listeners on the framework.
     */
    public void install() {
        framework.addBroadcasterListener(new AdminBroadcasterListener());
        logger.debug("Admin event producer installed");
    }

    private void emit(AdminEvent event) {
        BroadcasterFactory factory = framework.getBroadcasterFactory();
        var json = toJson(event);
        AdminEventHandler.broadcastEvent(factory, json);
    }

    private String toJson(AdminEvent event) {
        // Simple JSON serialization without Jackson dependency
        return switch (event) {
            case AdminEvent.ResourceConnected e -> String.format(
                    "{\"type\":\"ResourceConnected\",\"uuid\":\"%s\",\"transport\":\"%s\","
                            + "\"broadcaster\":\"%s\",\"timestamp\":\"%s\"}",
                    escape(e.uuid()), escape(e.transport()),
                    escape(e.broadcaster()), e.timestamp());
            case AdminEvent.ResourceDisconnected e -> String.format(
                    "{\"type\":\"ResourceDisconnected\",\"uuid\":\"%s\",\"reason\":\"%s\","
                            + "\"timestamp\":\"%s\"}",
                    escape(e.uuid()), escape(e.reason()), e.timestamp());
            case AdminEvent.BroadcasterCreated e -> String.format(
                    "{\"type\":\"BroadcasterCreated\",\"id\":\"%s\",\"timestamp\":\"%s\"}",
                    escape(e.id()), e.timestamp());
            case AdminEvent.BroadcasterDestroyed e -> String.format(
                    "{\"type\":\"BroadcasterDestroyed\",\"id\":\"%s\",\"timestamp\":\"%s\"}",
                    escape(e.id()), e.timestamp());
            case AdminEvent.MessageBroadcast e -> String.format(
                    "{\"type\":\"MessageBroadcast\",\"broadcasterId\":\"%s\","
                            + "\"resourceCount\":%d,\"timestamp\":\"%s\"}",
                    escape(e.broadcasterId()), e.resourceCount(), e.timestamp());
            case AdminEvent.AgentSessionStarted e -> String.format(
                    "{\"type\":\"AgentSessionStarted\",\"agentName\":\"%s\","
                            + "\"sessionId\":\"%s\",\"timestamp\":\"%s\"}",
                    escape(e.agentName()), escape(e.sessionId()), e.timestamp());
            case AdminEvent.AgentSessionEnded e -> String.format(
                    "{\"type\":\"AgentSessionEnded\",\"agentName\":\"%s\","
                            + "\"sessionId\":\"%s\",\"duration\":\"%s\","
                            + "\"messageCount\":%d,\"timestamp\":\"%s\"}",
                    escape(e.agentName()), escape(e.sessionId()),
                    e.duration(), e.messageCount(), e.timestamp());
            case AdminEvent.TaskStateChanged e -> String.format(
                    "{\"type\":\"TaskStateChanged\",\"taskId\":\"%s\","
                            + "\"oldState\":\"%s\",\"newState\":\"%s\",\"timestamp\":\"%s\"}",
                    escape(e.taskId()), escape(e.oldState()),
                    escape(e.newState()), e.timestamp());
            case AdminEvent.AgentDispatched e -> String.format(
                    "{\"type\":\"AgentDispatched\",\"coordinationId\":\"%s\","
                            + "\"agentName\":\"%s\",\"skill\":\"%s\",\"timestamp\":\"%s\"}",
                    escape(e.coordinationId()), escape(e.agentName()),
                    escape(e.skill()), e.timestamp());
            case AdminEvent.AgentCompleted e -> String.format(
                    "{\"type\":\"AgentCompleted\",\"coordinationId\":\"%s\","
                            + "\"agentName\":\"%s\",\"duration\":\"%s\",\"timestamp\":\"%s\"}",
                    escape(e.coordinationId()), escape(e.agentName()),
                    e.duration(), e.timestamp());
            case AdminEvent.ControlActionExecuted e -> String.format(
                    "{\"type\":\"ControlActionExecuted\",\"principal\":\"%s\","
                            + "\"action\":\"%s\",\"target\":\"%s\","
                            + "\"success\":%b,\"timestamp\":\"%s\"}",
                    escape(e.principal()), escape(e.action()),
                    escape(e.target()), e.success(), e.timestamp());
        };
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * Listens to broadcaster lifecycle events and produces admin events.
     * Skips the admin events broadcaster itself to avoid feedback loops.
     */
    private class AdminBroadcasterListener extends BroadcasterListenerAdapter {

        @Override
        public void onPostCreate(Broadcaster b) {
            if (isAdminBroadcaster(b)) {
                return;
            }
            emit(new AdminEvent.BroadcasterCreated(b.getID(), Instant.now()));
        }

        @Override
        public void onPreDestroy(Broadcaster b) {
            if (isAdminBroadcaster(b)) {
                return;
            }
            emit(new AdminEvent.BroadcasterDestroyed(b.getID(), Instant.now()));
        }

        @Override
        public void onAddAtmosphereResource(Broadcaster b, AtmosphereResource r) {
            if (isAdminBroadcaster(b)) {
                return;
            }
            emit(new AdminEvent.ResourceConnected(
                    r.uuid(), r.transport().name(), b.getID(), Instant.now()));

            // Track disconnections
            r.addEventListener(new AtmosphereResourceEventListenerAdapter() {
                @Override
                public void onDisconnect(AtmosphereResourceEvent event) {
                    emit(new AdminEvent.ResourceDisconnected(
                            r.uuid(), "client", Instant.now()));
                }

                @Override
                public void onClose(AtmosphereResourceEvent event) {
                    emit(new AdminEvent.ResourceDisconnected(
                            r.uuid(), "application", Instant.now()));
                }
            });
        }

        @Override
        public void onMessage(Broadcaster b, Deliver deliver) {
            if (isAdminBroadcaster(b)) {
                return;
            }
            emit(new AdminEvent.MessageBroadcast(
                    b.getID(), b.getAtmosphereResources().size(), Instant.now()));
        }

        private boolean isAdminBroadcaster(Broadcaster b) {
            return AdminEventHandler.ADMIN_BROADCASTER_ID.equals(b.getID());
        }
    }
}
