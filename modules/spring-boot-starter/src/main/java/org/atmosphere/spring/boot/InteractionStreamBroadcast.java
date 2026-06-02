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
package org.atmosphere.spring.boot;

import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.BroadcasterFactory;
import org.atmosphere.cpr.BroadcasterLifeCyclePolicy;
import org.atmosphere.interactions.Interaction;
import org.atmosphere.interactions.InteractionLiveStream;
import org.atmosphere.interactions.InteractionStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * {@link InteractionLiveStream.Factory} that broadcasts a background interaction's
 * steps to a per-interaction Atmosphere {@link Broadcaster} as they are captured,
 * so browsers subscribed via {@link InteractionStreamHandler} see the run live.
 *
 * <p>The {@link BroadcasterFactory} is resolved lazily (it only exists after
 * {@code AtmosphereServlet.init()}), so this factory is safe to construct at
 * bean-creation time and used later when a background run actually starts. Each
 * per-interaction broadcaster is created with {@link BroadcasterLifeCyclePolicy
 * EMPTY_DESTROY} so it self-reaps once the last subscriber leaves — no leak per
 * completed interaction (Correctness Invariant #3 — Backpressure).</p>
 */
final class InteractionStreamBroadcast implements InteractionLiveStream.Factory {

    private static final Logger LOGGER = LoggerFactory.getLogger(InteractionStreamBroadcast.class);

    private final Supplier<BroadcasterFactory> broadcasterFactory;

    InteractionStreamBroadcast(Supplier<BroadcasterFactory> broadcasterFactory) {
        this.broadcasterFactory = broadcasterFactory;
    }

    @Override
    public InteractionLiveStream open(Interaction initial) {
        var channel = InteractionStreamFrames.channelId(initial.id());
        return new InteractionLiveStream() {
            @Override
            public void onStep(InteractionStep step) {
                broadcast(InteractionStreamFrames.stepFrame(step));
            }

            @Override
            public void onTerminal(Interaction terminal) {
                broadcast(InteractionStreamFrames.terminalFrame(terminal));
            }

            private void broadcast(String json) {
                var broadcaster = lookup(channel);
                if (broadcaster != null) {
                    broadcaster.broadcast(json);
                }
            }
        };
    }

    private Broadcaster lookup(String channel) {
        var factory = broadcasterFactory.get();
        if (factory == null) {
            LOGGER.debug("BroadcasterFactory not ready — dropping live frame for {}", channel);
            return null;
        }
        var broadcaster = factory.lookup(channel, true);
        if (broadcaster != null) {
            // Self-destroy once the last subscriber disconnects so a per-interaction
            // channel does not outlive its run.
            broadcaster.setBroadcasterLifeCyclePolicy(BroadcasterLifeCyclePolicy.EMPTY_DESTROY);
        }
        return broadcaster;
    }
}
