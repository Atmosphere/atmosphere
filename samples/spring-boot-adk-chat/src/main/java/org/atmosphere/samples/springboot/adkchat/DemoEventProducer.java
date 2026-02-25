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
package org.atmosphere.samples.springboot.adkchat;

import com.google.adk.events.Event;
import com.google.adk.events.EventActions;
import com.google.genai.types.Content;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Flowable;

import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Produces a simulated ADK event stream for demo/testing purposes.
 * This allows testing the full Atmosphere + ADK pipeline without
 * requiring actual Gemini API credentials.
 */
public final class DemoEventProducer {

    private DemoEventProducer() {
    }

    /**
     * Create a simulated streaming response for the given user prompt.
     */
    public static Flowable<Event> stream(String userMessage) {
        var response = generateResponse(userMessage);
        var words = response.split("(?<=\\s)");

        return Flowable.fromArray(words)
                .zipWith(Flowable.interval(50, TimeUnit.MILLISECONDS), (word, tick) -> word)
                .map(DemoEventProducer::partialEvent)
                .concatWith(Flowable.just(turnCompleteEvent()));
    }

    private static String generateResponse(String userMessage) {
        var lower = userMessage.toLowerCase();
        if (lower.contains("hello") || lower.contains("hi")) {
            return "Hello! I'm an ADK agent running on Atmosphere Framework. "
                    + "I can stream responses in real-time to your browser via WebSocket, SSE, or gRPC. "
                    + "What would you like to know?";
        }
        if (lower.contains("atmosphere")) {
            return "Atmosphere is a Java framework for building real-time web applications. "
                    + "It supports WebSocket, SSE, long-polling, and gRPC transports. "
                    + "With the ADK integration, you can now stream Google ADK agent responses "
                    + "directly to connected browser clients using the Broadcaster pub/sub model.";
        }
        if (lower.contains("adk") || lower.contains("agent")) {
            return "Google ADK (Agent Development Kit) is an open-source toolkit for building "
                    + "AI agents with fine-grained control. It uses LlmAgent, Runner, and Flowable<Event> "
                    + "for streaming. The atmosphere-adk module bridges these event streams to "
                    + "Atmosphere's Broadcaster, so tokens reach browsers in real-time.";
        }
        return "I received your message: \"" + userMessage + "\". "
                + "This is a demo ADK agent streaming tokens through Atmosphere's "
                + "real-time infrastructure. Each word you see arrives as a separate "
                + "streaming token via WebSocket. Try asking about 'atmosphere' or 'adk'!";
    }

    private static Event partialEvent(String text) {
        return Event.builder()
                .id(Event.generateEventId())
                .invocationId("demo-invocation")
                .author("model")
                .actions(EventActions.builder().build())
                .partial(Optional.of(true))
                .content(Optional.of(Content.fromParts(Part.fromText(text))))
                .build();
    }

    private static Event turnCompleteEvent() {
        return Event.builder()
                .id(Event.generateEventId())
                .invocationId("demo-invocation")
                .author("model")
                .actions(EventActions.builder().build())
                .turnComplete(Optional.of(true))
                .build();
    }
}
