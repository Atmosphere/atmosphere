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
package org.atmosphere.samples.springboot.passivation;

import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.AiCapability;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.llm.ChatMessage;

import java.util.Set;

/**
 * A deterministic, key-free {@link AgentRuntime} so the passivation demo and
 * its delivery test run fully offline — the same {@code DemoResponseProducer}
 * spirit other Atmosphere samples use to work without an LLM key.
 *
 * <p>The proof this runtime carries is <b>continuation</b>: its response is a
 * function of the conversation history it is handed on resume. It reads
 * {@code context.history()} (the turns restored from the snapshot) and the
 * in-flight {@code context.message()} (the resume signal), then composes a
 * reply that quotes the earlier conversation. If resume had restarted the
 * agent cold — empty history — the quoted earlier turn would be absent. The
 * delivery test asserts the earlier turn IS present, which is exactly the
 * "resumes from where it left off" claim.</p>
 *
 * <p>Declares {@link AiCapability#PASSIVATION} because it threads
 * {@code context.history()} through its dispatch — the contract that flag
 * advertises (see {@link org.atmosphere.checkpoint.AgentPassivation}).</p>
 */
public final class DemoContinuationRuntime implements AgentRuntime {

    @Override
    public String name() {
        return "demo-continuation";
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
        // No external LLM — nothing to configure.
    }

    @Override
    public Set<AiCapability> capabilities() {
        return Set.of(AiCapability.TEXT_STREAMING, AiCapability.PASSIVATION);
    }

    @Override
    public void execute(AgentExecutionContext context, StreamingSession session) {
        var history = context.history();
        var restoredTurns = history.size();
        var earlierUserTurn = lastUserTurn(history);

        // Observable side effects the application (and the delivery test) read
        // back: how many turns were restored, and whether this was a warm
        // continuation rather than a cold start.
        session.sendMetadata("resumed.history.size", restoredTurns);
        session.sendMetadata("resumed.continued", restoredTurns > 0);
        session.sendMetadata("resumed.session.id", context.sessionId());

        var reply = new StringBuilder()
                .append("Resuming the conversation (")
                .append(restoredTurns)
                .append(" earlier message")
                .append(restoredTurns == 1 ? "" : "s")
                .append(" restored). ");
        if (earlierUserTurn != null) {
            reply.append("Earlier the customer said: \"")
                    .append(earlierUserTurn)
                    .append("\". ");
        }
        reply.append("Approval signal received: \"")
                .append(context.message())
                .append("\". Completing the request.");

        session.send(reply.toString());
        session.complete();
    }

    /** The content of the most recent {@code user} turn in {@code history}, or null. */
    private static String lastUserTurn(java.util.List<ChatMessage> history) {
        for (int i = history.size() - 1; i >= 0; i--) {
            var msg = history.get(i);
            if ("user".equalsIgnoreCase(msg.role())) {
                return msg.content();
            }
        }
        return null;
    }
}
