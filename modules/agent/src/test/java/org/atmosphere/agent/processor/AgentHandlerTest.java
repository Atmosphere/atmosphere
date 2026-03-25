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
package org.atmosphere.agent.processor;

import org.atmosphere.agent.annotation.Command;
import org.atmosphere.agent.command.CommandRegistry;
import org.atmosphere.agent.command.CommandResult;
import org.atmosphere.agent.command.CommandRouter;
import org.atmosphere.ai.processor.AiEndpointHandler;
import org.atmosphere.config.service.Message;
import org.atmosphere.config.managed.Decoder;
import org.atmosphere.config.managed.Encoder;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereRequestImpl;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.RawMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link AgentHandler} — validates command routing integration,
 * {@code @Message} routing, and LLM fallback behavior.
 */
public class AgentHandlerTest {

    // ── Test agent classes ──

    static class TestHandlerAgent {
        @Command(value = "/ping", description = "Ping")
        public String ping() {
            return "pong";
        }

        @Command(value = "/greet", description = "Greet user")
        public String greet(String name) {
            return "Hello, " + name + "!";
        }

        @Command(value = "/danger", confirm = "Are you sure?")
        public String danger() {
            return "Dangerous action executed.";
        }
    }

    /**
     * Agent class with a @Message method that returns a non-null value.
     */
    public static class MessageAgent {
        final AtomicReference<String> lastReceived = new AtomicReference<>();

        @Message
        public String onMessage(String msg) {
            lastReceived.set(msg);
            return "echo: " + msg;
        }
    }

    /**
     * Agent class with a @Message method that returns null (falls through to AI).
     */
    public static class NullMessageAgent {
        final AtomicReference<String> lastReceived = new AtomicReference<>();

        @Message
        public String onMessage(String msg) {
            lastReceived.set(msg);
            return null;
        }
    }

    /**
     * Agent class with no @Message method at all.
     */
    public static class NoMessageAgent {
        @Command(value = "/ping", description = "Ping")
        public String ping() {
            return "pong";
        }
    }

    /**
     * Agent class with both @Command and @Message — commands take priority.
     */
    public static class CommandAndMessageAgent {
        final AtomicReference<String> lastReceived = new AtomicReference<>();

        @Command(value = "/echo", description = "Echo back")
        public String echo(String args) {
            return "echoed: " + args;
        }

        @Message
        public String onMessage(String msg) {
            lastReceived.set(msg);
            return "handled: " + msg;
        }
    }

    /**
     * Encoder that wraps a String in brackets.
     */
    public static class BracketEncoder implements Encoder<String, String> {
        @Override
        public String encode(String s) {
            return "[" + s + "]";
        }
    }

    /**
     * Decoder that trims a String.
     */
    public static class TrimDecoder implements Decoder<String, String> {
        @Override
        public String decode(String s) {
            return s.trim();
        }
    }

    /**
     * Agent class with @Message using encoders and decoders.
     */
    public static class EncoderDecoderMessageAgent {
        @Message(encoders = {BracketEncoder.class}, decoders = {TrimDecoder.class})
        public String onMessage(String msg) {
            return msg;
        }
    }

    // ── Command routing tests ──

    private CommandRouter router;

    @BeforeEach
    void setUp() {
        var agent = new TestHandlerAgent();
        var registry = new CommandRegistry();
        registry.scan(TestHandlerAgent.class);
        router = new CommandRouter(registry, agent);
    }

    @Test
    public void testCommandRouting() {
        var result = router.route("client-1", "/ping");
        assertInstanceOf(CommandResult.Executed.class, result);
        assertEquals("pong", ((CommandResult.Executed) result).response());
    }

    @Test
    public void testCommandWithArgRouting() {
        var result = router.route("client-1", "/greet World");
        assertInstanceOf(CommandResult.Executed.class, result);
        assertEquals("Hello, World!", ((CommandResult.Executed) result).response());
    }

    @Test
    public void testNonCommandFallsThrough() {
        var result = router.route("client-1", "What's the weather?");
        assertInstanceOf(CommandResult.NotACommand.class, result);
    }

    @Test
    public void testConfirmationFlow() {
        // First: confirmation required
        var result1 = router.route("client-1", "/danger");
        assertInstanceOf(CommandResult.ConfirmationRequired.class, result1);
        assertEquals("Are you sure?", ((CommandResult.ConfirmationRequired) result1).prompt());

        // Second: confirm
        var result2 = router.route("client-1", "yes");
        assertInstanceOf(CommandResult.Executed.class, result2);
        assertEquals("Dangerous action executed.", ((CommandResult.Executed) result2).response());
    }

    @Test
    public void testHelpCommand() {
        var result = router.route("client-1", "/help");
        assertInstanceOf(CommandResult.Executed.class, result);
        var help = ((CommandResult.Executed) result).response();
        assertTrue(help.contains("/ping"));
        assertTrue(help.contains("/greet"));
        assertTrue(help.contains("/danger"));
        assertTrue(help.contains("/help"));
    }

    @Test
    public void testUnknownCommandFallsThrough() {
        var result = router.route("client-1", "/nonexistent");
        assertInstanceOf(CommandResult.NotACommand.class, result);
    }

    // ── @Message routing tests ──

    @Test
    public void testConstructorFindsMessageMethod() {
        var aiDelegate = mock(AiEndpointHandler.class);
        var agent = new MessageAgent();
        var registry = new CommandRegistry();
        var commandRouter = new CommandRouter(registry, agent);

        var handler = new AgentHandler(aiDelegate, commandRouter, agent, null);

        assertNotNull(handler, "Handler should be created with @Message target");
    }

    @Test
    public void testConstructorWithNullTarget() {
        var aiDelegate = mock(AiEndpointHandler.class);
        var registry = new CommandRegistry();
        var commandRouter = new CommandRouter(registry, new Object());

        var handler = new AgentHandler(aiDelegate, commandRouter, null, null);

        assertNotNull(handler, "Handler should be created with null messageTarget");
    }

    @Test
    public void testConstructorWithNoMessageMethod() {
        var aiDelegate = mock(AiEndpointHandler.class);
        var agent = new NoMessageAgent();
        var registry = new CommandRegistry();
        registry.scan(NoMessageAgent.class);
        var commandRouter = new CommandRouter(registry, agent);

        var handler = new AgentHandler(aiDelegate, commandRouter, agent, null);

        assertNotNull(handler, "Handler should be created even without @Message method");
    }

    @Test
    public void testMessageMethodInvokedOnPost() throws IOException {
        var agent = new MessageAgent();
        var aiDelegate = mock(AiEndpointHandler.class);
        var registry = new CommandRegistry();
        var commandRouter = new CommandRouter(registry, agent);
        var handler = new AgentHandler(aiDelegate, commandRouter, agent, null);

        var resource = mock(AtmosphereResource.class);
        var request = mock(AtmosphereRequest.class);
        var broadcaster = mock(Broadcaster.class);
        when(resource.getRequest()).thenReturn(request);
        when(resource.getBroadcaster()).thenReturn(broadcaster);
        when(resource.uuid()).thenReturn("client-1");
        when(request.getMethod()).thenReturn("POST");
        when(request.body()).thenReturn(new AtmosphereRequestImpl.Body.StringBody("hello world"));

        handler.onRequest(resource);

        assertEquals("hello world", agent.lastReceived.get(),
                "@Message method should have received the raw message");

        // Verify broadcast was called (non-null return means broadcast)
        var captor = ArgumentCaptor.forClass(RawMessage.class);
        verify(broadcaster).broadcast(captor.capture());

        var broadcastedMsg = captor.getValue();
        assertEquals("echo: hello world", broadcastedMsg.message(),
                "Broadcast should contain the @Message return value");

        // AI delegate should NOT have been called
        verify(aiDelegate, never()).onRequest(any());
    }

    @Test
    public void testNullReturnFallsThroughToAi() throws IOException {
        var agent = new NullMessageAgent();
        var aiDelegate = mock(AiEndpointHandler.class);
        var registry = new CommandRegistry();
        var commandRouter = new CommandRouter(registry, agent);
        var handler = new AgentHandler(aiDelegate, commandRouter, agent, null);

        var resource = mock(AtmosphereResource.class);
        var request = mock(AtmosphereRequest.class);
        when(resource.getRequest()).thenReturn(request);
        when(resource.uuid()).thenReturn("client-1");
        when(request.getMethod()).thenReturn("POST");
        when(request.body()).thenReturn(new AtmosphereRequestImpl.Body.StringBody("hello"));

        handler.onRequest(resource);

        assertEquals("hello", agent.lastReceived.get(),
                "@Message method should have been invoked");

        // When @Message returns null, AI delegate should be called
        verify(aiDelegate).onRequest(resource);
    }

    @Test
    public void testNoMessageMethodGoesDirectlyToAi() throws IOException {
        var agent = new NoMessageAgent();
        var aiDelegate = mock(AiEndpointHandler.class);
        var registry = new CommandRegistry();
        var commandRouter = new CommandRouter(registry, agent);
        var handler = new AgentHandler(aiDelegate, commandRouter, agent, null);

        var resource = mock(AtmosphereResource.class);
        var request = mock(AtmosphereRequest.class);
        when(resource.getRequest()).thenReturn(request);
        when(resource.uuid()).thenReturn("client-1");
        when(request.getMethod()).thenReturn("POST");
        when(request.body()).thenReturn(new AtmosphereRequestImpl.Body.StringBody("hello"));

        handler.onRequest(resource);

        // No @Message method -> should fall through to AI pipeline
        verify(aiDelegate).onRequest(resource);
    }

    @Test
    public void testCommandsTakePriorityOverMessage() throws IOException {
        var agent = new CommandAndMessageAgent();
        var aiDelegate = mock(AiEndpointHandler.class);
        var registry = new CommandRegistry();
        registry.scan(CommandAndMessageAgent.class);
        var commandRouter = new CommandRouter(registry, agent);
        var handler = new AgentHandler(aiDelegate, commandRouter, agent, null);

        var resource = mock(AtmosphereResource.class);
        var request = mock(AtmosphereRequest.class);
        when(resource.getRequest()).thenReturn(request);
        when(resource.uuid()).thenReturn("client-1");
        when(request.getMethod()).thenReturn("POST");
        when(request.body()).thenReturn(new AtmosphereRequestImpl.Body.StringBody("/echo test"));

        handler.onRequest(resource);

        // Command should have been handled — @Message should NOT be invoked
        assertNull(agent.lastReceived.get(),
                "@Message should not be invoked when a command matches");
        verify(aiDelegate, never()).onRequest(any());
    }

    @Test
    public void testGetRequestDelegatesToAi() throws IOException {
        var agent = new MessageAgent();
        var aiDelegate = mock(AiEndpointHandler.class);
        var registry = new CommandRegistry();
        var commandRouter = new CommandRouter(registry, agent);
        var handler = new AgentHandler(aiDelegate, commandRouter, agent, null);

        var resource = mock(AtmosphereResource.class);
        var request = mock(AtmosphereRequest.class);
        when(resource.getRequest()).thenReturn(request);
        when(request.getMethod()).thenReturn("GET");

        handler.onRequest(resource);

        // GET requests skip command routing and @Message entirely
        assertNull(agent.lastReceived.get());
        verify(aiDelegate).onRequest(resource);
    }

    @Test
    public void testEncoderDecoderInstantiationWithConfig() {
        var aiDelegate = mock(AiEndpointHandler.class);
        var agent = new EncoderDecoderMessageAgent();
        var registry = new CommandRegistry();
        var commandRouter = new CommandRouter(registry, agent);

        var config = mock(AtmosphereConfig.class);
        var framework = mock(AtmosphereFramework.class);
        when(config.framework()).thenReturn(framework);

        try {
            when(framework.newClassInstance(eq(Encoder.class), any()))
                    .thenReturn(new BracketEncoder());
            when(framework.newClassInstance(eq(Decoder.class), any()))
                    .thenReturn(new TrimDecoder());
        } catch (Exception e) {
            fail("Mock setup should not throw: " + e.getMessage());
        }

        var handler = new AgentHandler(aiDelegate, commandRouter, agent, config);

        assertNotNull(handler, "Handler with encoders/decoders should be created");
    }

    @Test
    public void testTwoArgConstructorHasNoMessageMethod() throws IOException {
        var aiDelegate = mock(AiEndpointHandler.class);
        var registry = new CommandRegistry();
        var commandRouter = new CommandRouter(registry, new Object());

        // Two-arg constructor — no messageTarget
        var handler = new AgentHandler(aiDelegate, commandRouter);

        var resource = mock(AtmosphereResource.class);
        var request = mock(AtmosphereRequest.class);
        when(resource.getRequest()).thenReturn(request);
        when(resource.uuid()).thenReturn("client-1");
        when(request.getMethod()).thenReturn("POST");
        when(request.body()).thenReturn(new AtmosphereRequestImpl.Body.StringBody("hello"));

        handler.onRequest(resource);

        // No messageTarget -> AI delegate called
        verify(aiDelegate).onRequest(resource);
    }
}
