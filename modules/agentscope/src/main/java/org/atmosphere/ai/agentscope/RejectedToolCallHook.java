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
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import org.atmosphere.ai.AiEvent;
import org.atmosphere.ai.StreamingSession;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Reports a tool call that AgentScope answered without running the tool.
 *
 * <p>AgentScope's {@code ToolExecutor} checks a call before it hands it to the
 * tool: a call that names no tool of the toolkit, a tool of an inactive group,
 * or arguments that fail the tool's JSON schema ("Parameter validation failed
 * for tool …") are answered with an error result the model reads, and the tool
 * — the bridge that routes through {@code ToolExecutionHelper} and emits the
 * call's frames — is never called. On a {@link PostActingEvent} for such a
 * call this hook emits what {@code ToolExecutionHelper} emits for a call it
 * rejects: one {@link AiEvent.ToolStart} and one {@link AiEvent.ToolError}
 * carrying AgentScope's message. The result the model reads is left as
 * AgentScope made it. Calls to AgentScope's own tools (the structured-output
 * {@code generate_response}, the plan notebook's tools) are not reported, nor
 * is anything once the turn's session has ended.</p>
 */
final class RejectedToolCallHook implements Hook {

    private static final String ERROR_PREFIX = "Error: ";

    private final StreamingSession session;

    RejectedToolCallHook(StreamingSession session) {
        this.session = session;
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PostActingEvent acting && acting.getToolUse() != null) {
            var use = acting.getToolUse();
            var toolkit = acting.getToolkit();
            var agentTool = toolkit != null ? toolkit.getTool(use.getName()) : null;
            // null: not a bridge tool (AgentScope's own) — not ours to report.
            var reached = agentTool != null ? AgentScopeToolBridge.reachedTool(agentTool, use) : Boolean.FALSE;
            if (Boolean.FALSE.equals(reached) && !session.isClosed()) {
                var name = use.getName() != null ? use.getName() : "";
                var args = use.getInput() != null ? use.getInput() : Map.<String, Object>of();
                session.emit(new AiEvent.ToolStart(name, args));
                session.emit(new AiEvent.ToolError(name, errorOf(acting.getToolResult())));
            }
        }
        return Mono.just(event);
    }

    /** The text AgentScope answered the call with, without its "Error: " prefix. */
    static String errorOf(ToolResultBlock result) {
        var text = new StringBuilder();
        if (result != null && result.getOutput() != null) {
            for (var block : result.getOutput()) {
                if (block instanceof TextBlock textBlock && textBlock.getText() != null) {
                    text.append(textBlock.getText());
                }
            }
        }
        var message = text.toString().strip();
        if (message.startsWith(ERROR_PREFIX)) {
            message = message.substring(ERROR_PREFIX.length()).strip();
        }
        return message.isEmpty() ? "The tool call was rejected before the tool ran." : message;
    }
}
