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
package org.atmosphere.samples.springboot.aitools;

import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.AiStreamingSession;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.StreamingSessions;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.RawMessage;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Proves {@link CostMeteringInterceptor}'s {@code routing.*} emission lands on
 * the wire BEFORE the terminal {@code complete} frame — the interceptor path
 * complement to {@link CostLatencyRoutingDeliveryTest}, which covers the
 * {@code RoutingLlmClient} producer.
 *
 * <p>The delegate is a REAL {@code DefaultStreamingSession} (via
 * {@link StreamingSessions#start}) whose broadcast frames are captured at the
 * wire: runtimes complete the session mid-execute, so a closed leaf silently
 * drops late metadata — exactly the bug that made the Console's routing chips
 * stay empty when the interceptor emitted from {@code postProcess}. The
 * atmosphere.js client snapshots routing state AT the complete frame, so the
 * order assertion here is the contract that makes the chips render.</p>
 */
class CostMeteringInterceptorDeliveryTest {

    @Test
    void routingMetadataReachesTheWireBeforeTheCompleteFrame() {
        var frames = new CopyOnWriteArrayList<String>();
        var attributes = new HashMap<String, Object>();

        var request = mock(AtmosphereRequest.class);
        doAnswer(inv -> {
            attributes.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(request).setAttribute(anyString(), any());
        when(request.getAttribute(anyString()))
                .thenAnswer(inv -> attributes.get(inv.<String>getArgument(0)));

        var broadcaster = mock(Broadcaster.class);
        when(broadcaster.broadcast(any(), anySet())).thenAnswer(inv -> {
            frames.add(String.valueOf(((RawMessage) inv.getArgument(0)).message()));
            return null;
        });

        var resource = mock(AtmosphereResource.class);
        when(resource.getRequest()).thenReturn(request);
        when(resource.uuid()).thenReturn("cost-metering-uuid");
        when(resource.getBroadcaster()).thenReturn(broadcaster);

        // Runtime shaped like the real bridges (and DemoAgentRuntime): it
        // completes the session mid-execute, so anything emitted after the
        // dispatch returns is talking to a closed leaf.
        var runtime = new AgentRuntime() {
            @Override
            public String name() {
                return "demo-like";
            }

            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public int priority() {
                return 0;
            }

            @Override
            public void configure(AiConfig.LlmSettings settings) {
            }

            @Override
            public void execute(AgentExecutionContext context, StreamingSession session) {
                session.send("It is sunny in Paris today.");
                session.complete();
            }
        };

        var session = new AiStreamingSession(
                StreamingSessions.start("cost-metering-session", resource), runtime,
                "You are a helpful assistant.", "demo-model",
                List.of(new CostMeteringInterceptor()), resource);

        session.stream("What is the weather in Paris?");

        var completeIdx = indexOfFrame(frames, "\"type\":\"complete\"");
        assertTrue(completeIdx >= 0, "terminal complete frame missing; frames: " + frames);
        for (var key : List.of("routing.model", "routing.cost", "routing.streamingTexts")) {
            var idx = indexOfFrame(frames, "\"" + key + "\"");
            assertTrue(idx >= 0, key + " metadata must reach the wire; frames: " + frames);
            assertTrue(idx < completeIdx, key + " must precede the terminal complete frame "
                    + "(the Console snapshots routing state at complete); frames: " + frames);
        }
        assertTrue(indexOfFrame(frames, "\"value\":\"demo-model\"") >= 0,
                "routing.model must carry the estimated model; frames: " + frames);
    }

    private static int indexOfFrame(List<String> frames, String token) {
        for (int i = 0; i < frames.size(); i++) {
            if (frames.get(i).contains(token)) {
                return i;
            }
        }
        return -1;
    }
}
