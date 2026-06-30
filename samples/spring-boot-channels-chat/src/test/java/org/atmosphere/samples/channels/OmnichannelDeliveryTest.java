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
package org.atmosphere.samples.channels;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.atmosphere.agent.annotation.Agent;
import org.atmosphere.agent.command.CommandRegistry;
import org.atmosphere.agent.command.CommandRouter;
import org.atmosphere.agent.skill.SkillFileParser;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.PromptLoader;
import org.atmosphere.ai.annotation.AiEndpoint;
import org.atmosphere.channels.ChannelAiBridge;
import org.atmosphere.channels.ChannelFilterChain;
import org.atmosphere.channels.ChannelType;
import org.atmosphere.channels.DeliveryReceipt;
import org.atmosphere.channels.IncomingMessage;
import org.atmosphere.channels.MessagingChannel;
import org.atmosphere.channels.OutgoingMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the {@code @Agent} conversion of the omnichannel sample:
 * <ol>
 *   <li>{@link OmnichannelChat} is an {@code @Agent}, not an {@code @AiEndpoint}.</li>
 *   <li>The skill file {@code skill:omnichannel-chat} carries the system prompt and a
 *       {@code ## Channels} allow-list of real {@link ChannelType} ids.</li>
 *   <li>Driving an inbound message from each declared channel through the real
 *       {@link ChannelAiBridge} delivers a reply back through that same channel —
 *       proving the agent's multichannel wiring is intact end-to-end.</li>
 * </ol>
 */
class OmnichannelDeliveryTest {

    @BeforeEach
    void noKey() {
        // mode=fake → AiConfig.get() non-null but apiKey() null: the documented
        // no-key demo path, so the bridge answers without any network call.
        AiConfig.configure("fake", "demo", null, null);
        assertNotNull(AiConfig.get(), "settings should be configured");
        assertTrue(AiConfig.get().apiKey() == null || AiConfig.get().apiKey().isBlank(),
                "fake mode must have no apiKey so the demo path is taken");
    }

    @Test
    void omnichannelChatIsAnAgentNotAnAiEndpoint() {
        assertTrue(OmnichannelChat.class.isAnnotationPresent(Agent.class),
                "OmnichannelChat must lead with @Agent");
        var agent = OmnichannelChat.class.getAnnotation(Agent.class);
        assertEquals("omnichannel", agent.name());
        assertEquals("skill:omnichannel-chat", agent.skillFile());
        assertFalse(OmnichannelChat.class.isAnnotationPresent(AiEndpoint.class),
                "OmnichannelChat must no longer be an @AiEndpoint");
    }

    @Test
    void skillFileCarriesSystemPromptAndChannelAllowList() {
        var prompt = PromptLoader.resolve("skill:omnichannel-chat");
        assertNotNull(prompt, "skill:omnichannel-chat must resolve from the classpath");
        assertTrue(prompt.contains("helpful AI assistant"),
                "system prompt must carry over the original persona");

        var channels = SkillFileParser.parse(prompt).listItems("Channels");
        assertEquals(List.of("telegram", "slack", "discord", "whatsapp", "messenger"), channels,
                "## Channels must list the bare channel ids the bridge allow-list matches on");
        // Every declared channel id must map to a real ChannelType (so the
        // allow-list actually admits the platform rather than silently dropping it).
        for (var id : channels) {
            assertEquals(id, ChannelType.valueOf(id.toUpperCase(Locale.ROOT)).id());
        }
    }

    @Test
    void agentDeliversAReplyOnEveryDeclaredChannel() throws InterruptedException {
        var skill = SkillFileParser.parse(PromptLoader.resolve("skill:omnichannel-chat"));
        var allowed = skill.listItems("Channels");

        // A recording fake channel per declared type.
        Map<ChannelType, RecordingChannel> channels = new EnumMap<>(ChannelType.class);
        for (var id : allowed) {
            var type = ChannelType.valueOf(id.toUpperCase(Locale.ROOT));
            channels.put(type, new RecordingChannel(type));
        }

        var bridge = new ChannelAiBridge(new ArrayList<>(channels.values()),
                new ChannelFilterChain(List.of()));

        // Register the real agent exactly as AgentProcessor would: its CommandRouter
        // (the sample declares no @Command, so natural language falls through to the
        // AI), its skill-file system prompt, and its ## Channels allow-list.
        var agent = new OmnichannelChat();
        var registry = new CommandRegistry();
        registry.scan(OmnichannelChat.class);
        var router = new CommandRouter(registry, agent);
        ChannelAiBridge.registerAgent("omnichannel", router, agent,
                skill.systemPrompt(), null, allowed);

        // Drive one inbound message from each declared channel; assert a non-empty
        // reply is delivered back through that same channel.
        for (var entry : channels.entrySet()) {
            var type = entry.getKey();
            var recorder = entry.getValue();
            var marker = "ping from " + type.id();
            var incoming = new IncomingMessage(type, "user-" + type.id(),
                    Optional.of("Tester"), marker,
                    "conv-" + type.id(), "msg-" + type.id(), Instant.now());

            bridge.handleMessage(incoming);

            var sent = recorder.awaitSend(15, TimeUnit.SECONDS);
            assertNotNull(sent, "no reply delivered on channel " + type.id());
            assertFalse(sent.text().isBlank(), "empty reply on channel " + type.id());
            // The no-key demo path echoes the inbound text — proves this exact
            // message was routed to the agent and the reply came back on this channel.
            assertTrue(sent.text().contains(marker),
                    "reply on " + type.id() + " must reference the inbound message, was: "
                            + sent.text());
        }
    }

    /** A {@link MessagingChannel} that records the {@link OutgoingMessage} it is asked to send. */
    private static final class RecordingChannel implements MessagingChannel {
        private final ChannelType type;
        private final LinkedBlockingQueue<OutgoingMessage> sent = new LinkedBlockingQueue<>();

        RecordingChannel(ChannelType type) {
            this.type = type;
        }

        OutgoingMessage awaitSend(long timeout, TimeUnit unit) throws InterruptedException {
            return sent.poll(timeout, unit);
        }

        @Override public ChannelType channelType() {
            return type;
        }

        @Override public String webhookPath() {
            return "/webhook/" + type.id();
        }

        @Override public void verifySignature(Map<String, String> headers, byte[] body) {
            // No verification in the test harness.
        }

        @Override public List<IncomingMessage> receive(Map<String, String> headers, byte[] body) {
            return List.of();
        }

        @Override public DeliveryReceipt send(OutgoingMessage message) {
            sent.add(message);
            return new DeliveryReceipt(Optional.of("test-" + type.id()),
                    DeliveryReceipt.DeliveryStatus.SENT, Instant.now());
        }

        @Override public int maxMessageLength() {
            return type.maxLength();
        }
    }
}
