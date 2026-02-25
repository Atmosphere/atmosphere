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
package org.atmosphere.ai.adk;

import com.google.adk.tools.BaseTool;
import com.google.adk.tools.ToolContext;
import com.google.genai.types.FunctionDeclaration;
import com.google.genai.types.Schema;
import com.google.genai.types.Type;
import io.reactivex.rxjava3.core.Single;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.BroadcasterFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;

/**
 * An ADK {@link BaseTool} that broadcasts messages to Atmosphere clients.
 *
 * <p>When an ADK agent calls this tool, the message is pushed to all
 * WebSocket/SSE/gRPC clients subscribed to the specified Atmosphere
 * broadcaster topic.</p>
 *
 * <p>Usage with ADK agent builder:</p>
 * <pre>{@code
 * var broadcastTool = new AdkBroadcastTool(broadcasterFactory);
 *
 * LlmAgent agent = LlmAgent.builder()
 *     .name("assistant")
 *     .model("gemini-2.0-flash")
 *     .instruction("Use the broadcast tool to push updates to users")
 *     .tools(broadcastTool)
 *     .build();
 * }</pre>
 */
public class AdkBroadcastTool extends BaseTool {

    private static final Logger logger = LoggerFactory.getLogger(AdkBroadcastTool.class);

    private final BroadcasterFactory broadcasterFactory;
    private final String defaultTopic;
    private final Broadcaster fixedBroadcaster;

    /**
     * Create a broadcast tool that sends to a specific default topic.
     *
     * @param broadcaster the Atmosphere broadcaster to push messages to
     */
    public AdkBroadcastTool(Broadcaster broadcaster) {
        super("broadcast", "Broadcast a message to all connected browser clients on an Atmosphere topic");
        this.broadcasterFactory = null;
        this.defaultTopic = broadcaster.getID();
        this.fixedBroadcaster = broadcaster;
    }

    /**
     * Create a broadcast tool that can send to any topic by name.
     *
     * @param broadcasterFactory the factory to look up broadcasters
     */
    public AdkBroadcastTool(BroadcasterFactory broadcasterFactory) {
        super("broadcast", "Broadcast a message to all connected browser clients on an Atmosphere topic");
        this.broadcasterFactory = broadcasterFactory;
        this.defaultTopic = null;
        this.fixedBroadcaster = null;
    }

    @Override
    public Optional<FunctionDeclaration> declaration() {
        var builder = FunctionDeclaration.builder()
                .name(name())
                .description(description());

        if (defaultTopic != null) {
            builder.parameters(Schema.builder()
                    .type(Type.Known.OBJECT)
                    .properties(Map.of(
                            "message", Schema.builder()
                                    .type(Type.Known.STRING)
                                    .description("The message to broadcast to connected clients")
                                    .build()
                    ))
                    .required(java.util.List.of("message"))
                    .build());
        } else {
            builder.parameters(Schema.builder()
                    .type(Type.Known.OBJECT)
                    .properties(Map.of(
                            "message", Schema.builder()
                                    .type(Type.Known.STRING)
                                    .description("The message to broadcast to connected clients")
                                    .build(),
                            "topic", Schema.builder()
                                    .type(Type.Known.STRING)
                                    .description("The broadcaster topic/channel to send to (e.g., '/chat')")
                                    .build()
                    ))
                    .required(java.util.List.of("message", "topic"))
                    .build());
        }

        return Optional.of(builder.build());
    }

    @Override
    public Single<Map<String, Object>> runAsync(Map<String, Object> args, ToolContext toolContext) {
        var message = (String) args.get("message");
        if (message == null || message.isBlank()) {
            return Single.just(Map.of("status", "error", "error", "message is required"));
        }

        try {
            Broadcaster broadcaster = resolveBroadcaster(args);
            broadcaster.broadcast(message);
            logger.debug("Broadcast to {}: {}", broadcaster.getID(), message);
            return Single.just(Map.of(
                    "status", "success",
                    "topic", broadcaster.getID(),
                    "recipients", broadcaster.getAtmosphereResources().size()
            ));
        } catch (Exception e) {
            logger.error("Failed to broadcast", e);
            return Single.just(Map.of("status", "error", "error", e.getMessage()));
        }
    }

    private Broadcaster resolveBroadcaster(Map<String, Object> args) {
        if (fixedBroadcaster != null) {
            return fixedBroadcaster;
        }

        var topic = (String) args.get("topic");
        if (topic == null && defaultTopic != null) {
            topic = defaultTopic;
        }
        if (topic == null) {
            throw new IllegalArgumentException("topic is required when no default broadcaster is set");
        }
        if (broadcasterFactory == null) {
            throw new IllegalStateException("BroadcasterFactory is not available");
        }
        return broadcasterFactory.lookup(topic, true);
    }
}
