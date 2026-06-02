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
package org.atmosphere.interactions;

/**
 * Transport-agnostic live sink for one background interaction. The
 * {@link InteractionService} pushes each durable step here as it is captured
 * and a final terminal notification when the run finishes, so a transport layer
 * (e.g. an Atmosphere Broadcaster in the Spring Boot starter) can stream the run
 * to subscribed browsers in real time.
 *
 * <p>This interface deliberately knows nothing about HTTP, WebSocket, or
 * Broadcasters — that keeps {@code modules/interactions} free of a transport
 * dependency. A {@code Factory} mints one stream per background interaction;
 * implementations are supplied by the starter via
 * {@link InteractionService}'s constructor.</p>
 */
public interface InteractionLiveStream {

    /** A durable step was captured (already coalesced); broadcast it live. */
    void onStep(InteractionStep step);

    /**
     * The run reached a terminal state. Carries the final record so subscribers
     * see the outcome even when no terminal step was emitted (e.g. CANCELLED,
     * which retains captured steps but appends no completion step).
     */
    void onTerminal(Interaction terminal);

    /** Mints a live stream for a single interaction (or {@code null} to skip). */
    @FunctionalInterface
    interface Factory {
        InteractionLiveStream open(Interaction initial);
    }
}
