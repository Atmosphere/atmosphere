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
package org.atmosphere.ai.agentscope;

import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostActingEvent;
import org.atmosphere.ai.StreamingSession;
import reactor.core.publisher.Mono;

/**
 * Stops a per-request {@code ReActAgent} after a tool round that ended the
 * turn it serves (an approval that ran out with the turn's time limit closes
 * the session, a cancel of the turn): AgentScope's own stop on a
 * {@link PostActingEvent} returns instead of reasoning again, so the model is
 * called no more and its answer does not hold the turn past its end.
 */
final class TurnEndedHook implements Hook {

    private final StreamingSession session;

    TurnEndedHook(StreamingSession session) {
        this.session = session;
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PostActingEvent acting
                && (session.isClosed() || session.isTurnCancelled())) {
            acting.stopAgent();
        }
        return Mono.just(event);
    }
}
