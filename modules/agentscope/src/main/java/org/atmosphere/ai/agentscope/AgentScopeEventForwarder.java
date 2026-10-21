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

import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.TokenUsage;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Forwards the events of one {@code ReActAgent.stream(...)} dispatch to the
 * turn's {@link StreamingSession}, so each answer reaches the client once.
 *
 * <p>With {@code StreamOptions.defaults()} AgentScope emits every model round
 * three ways: its incremental chunks ({@code REASONING}/{@code SUMMARY},
 * {@code isLast=false}), the round's whole message once it is complete
 * ({@code isLast=true}), and, for the round that ends the call, that same
 * message again as {@code AGENT_RESULT}. Only the chunks carry new text. The
 * whole message is forwarded only when no chunk carried text for its round (a
 * model that does not stream), and {@code AGENT_RESULT} only when it is not the
 * last round's message (the answer a structured-output call extracts from its
 * {@code generate_response} tool call). Token usage is reported once per round:
 * a repeated {@code AGENT_RESULT} reports none.</p>
 *
 * <p>Not thread-safe: Reactor delivers one subscriber's events serially.</p>
 */
final class AgentScopeEventForwarder {

    private final StreamingSession session;
    private final AtomicReference<TokenUsage> lastUsage;

    /** Whether a chunk of the round in progress carried text. */
    private boolean roundStreamedText;
    /** The text of the last completed round's message. */
    private String lastRoundText;

    AgentScopeEventForwarder(StreamingSession session, AtomicReference<TokenUsage> lastUsage) {
        this.session = session;
        this.lastUsage = lastUsage;
    }

    void accept(Event event) {
        if (event == null || event.getMessage() == null) {
            return;
        }
        var msg = event.getMessage();
        var text = msg.getTextContent();
        var hasText = text != null && !text.isEmpty();
        var type = event.getType();
        if (type == EventType.REASONING || type == EventType.SUMMARY) {
            if (!event.isLast()) {
                if (hasText) {
                    roundStreamedText = true;
                    session.send(text);
                }
                return;
            }
            // The round's whole message: its text already went out chunk by
            // chunk unless the model did not stream.
            if (hasText && !roundStreamedText) {
                session.send(text);
            }
            roundStreamedText = false;
            lastRoundText = text;
            reportUsage(event);
            return;
        }
        if (type == EventType.AGENT_RESULT && repeatsLastRound(text)) {
            return;
        }
        if (hasText) {
            session.send(text);
        }
        reportUsage(event);
    }

    /**
     * Whether the call's result is the last round's message again. Compared on
     * the text, not the message id: a structured-output result can keep the id
     * of the round that called {@code generate_response} while carrying the
     * extracted answer that round never streamed.
     */
    private boolean repeatsLastRound(String text) {
        return lastRoundText != null && Objects.equals(text, lastRoundText);
    }

    /**
     * Token usage rides on the message that completes a round. Forwarding it on
     * every chunk would double-count; AgentScope sets ChatUsage only on that
     * message. Stashed in {@code lastUsage} so the onModelEnd lifecycle hook
     * reports it.
     */
    private void reportUsage(Event event) {
        var usage = event.getMessage().getChatUsage();
        if (usage != null && event.isLast()) {
            var tokenUsage = TokenUsage.fromCounts(
                    (long) usage.getInputTokens(),
                    (long) usage.getOutputTokens(),
                    null,
                    (long) usage.getTotalTokens());
            session.usage(tokenUsage);
            lastUsage.set(tokenUsage);
        }
    }
}
