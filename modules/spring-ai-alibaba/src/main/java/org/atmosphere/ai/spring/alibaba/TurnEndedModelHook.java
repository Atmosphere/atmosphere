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
package org.atmosphere.ai.spring.alibaba;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.Prioritized;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.agent.hook.HookPositions;
import com.alibaba.cloud.ai.graph.agent.hook.JumpTo;
import com.alibaba.cloud.ai.graph.agent.hook.messages.AgentCommand;
import com.alibaba.cloud.ai.graph.agent.hook.messages.MessagesModelHook;
import org.atmosphere.ai.StreamingSession;
import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * Ends a per-request ReAct run before its next model call once the turn it
 * serves has ended: a tool round that ended the turn (an approval that ran out
 * with the turn's time limit closes the session, a cancel of the turn) calls
 * the model no more, so its answer does not hold the turn past its end.
 */
@HookPositions({HookPosition.BEFORE_MODEL})
final class TurnEndedModelHook extends MessagesModelHook {

    private final StreamingSession session;

    TurnEndedModelHook(StreamingSession session) {
        this.session = session;
    }

    @Override
    public String getName() {
        return "atmosphere_turn_ended_model_hook";
    }

    @Override
    public int getOrder() {
        return Prioritized.HIGHEST_PRECEDENCE;
    }

    @Override
    public List<JumpTo> canJumpTo() {
        return List.of(JumpTo.end);
    }

    @Override
    public AgentCommand beforeModel(List<Message> previousMessages, RunnableConfig config) {
        if (session.isClosed() || session.isTurnCancelled()) {
            return new AgentCommand(JumpTo.end, null);
        }
        return super.beforeModel(previousMessages, config);
    }
}
