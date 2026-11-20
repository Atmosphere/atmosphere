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
import io.agentscope.core.hook.PreActingEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import org.atmosphere.ai.StreamIdleWatchdog;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The model of a {@code ReActAgent} Atmosphere rebuilt for one turn (a turn
 * with tools or a plan), wrapping the bean's model. Every chunk the model
 * streams is activity on the turn's {@link StreamIdleWatchdog}, and
 * {@link #abort()} cancels the model call in flight: each call's {@code Flux}
 * runs {@code takeUntilOther} the turn's abort signal, so the abort cancels the
 * inner model {@code Flux}, which for AgentScope's HTTP models closes the
 * response body (their transport streams inside a {@code Flux.using}). A call
 * that starts after the abort is cancelled at once.
 *
 * <p>Its {@link #actingPause() hook} pauses the turn's clock while AgentScope
 * runs a tool, from the {@link PreActingEvent} to the {@link PostActingEvent}
 * (keyed by the tool use's id): a tool AgentScope runs natively is not idle
 * time. The model's next chunk, or {@link #ended()}, closes a pause whose end
 * was never reported.</p>
 */
final class AbortableModel implements Model {

    private final Model delegate;
    private final Optional<StreamIdleWatchdog> watchdog;
    private final Sinks.One<Boolean> abort = Sinks.one();
    private final Map<Object, StreamIdleWatchdog.Pause> toolPauses = new ConcurrentHashMap<>();

    AbortableModel(Model delegate, Optional<StreamIdleWatchdog> watchdog) {
        this.delegate = delegate;
        this.watchdog = watchdog;
    }

    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        return Flux.defer(() -> delegate.stream(messages, tools, options))
                .doOnSubscribe(ignored -> activity())
                .doOnNext(ignored -> {
                    resumeAll();
                    activity();
                })
                .takeUntilOther(abort.asMono());
    }

    @Override
    public String getModelName() {
        return delegate.getModelName();
    }

    /** Cancels the model call in flight, and any later one. */
    void abort() {
        abort.tryEmitValue(Boolean.TRUE);
        resumeAll();
    }

    /** The turn ended: no pause it opened may outlive it. */
    void ended() {
        resumeAll();
    }

    /** @return the hook that pauses the turn's clock while AgentScope runs a tool */
    Hook actingPause() {
        return new Hook() {
            @Override
            public <T extends HookEvent> Mono<T> onEvent(T event) {
                if (event instanceof PreActingEvent pre) {
                    toolStarted(key(pre.getToolUse()));
                } else if (event instanceof PostActingEvent post) {
                    toolEnded(key(post.getToolUse()));
                }
                return Mono.just(event);
            }
        };
    }

    /** @return how many tools hold the clock paused (tests) */
    int pausedTools() {
        return toolPauses.size();
    }

    private static Object key(io.agentscope.core.message.ToolUseBlock toolUse) {
        if (toolUse == null) {
            return AbortableModel.class;
        }
        return toolUse.getId() != null ? toolUse.getId() : toolUse;
    }

    private void activity() {
        watchdog.ifPresent(StreamIdleWatchdog::activity);
    }

    private void toolStarted(Object key) {
        activity();
        watchdog.ifPresent(w -> {
            var previous = toolPauses.put(key, w.pause());
            if (previous != null) {
                previous.close();
            }
        });
    }

    private void toolEnded(Object key) {
        var pause = toolPauses.remove(key);
        if (pause != null) {
            pause.close();
        }
        activity();
    }

    private void resumeAll() {
        for (var key : toolPauses.keySet()) {
            var pause = toolPauses.remove(key);
            if (pause != null) {
                pause.close();
            }
        }
    }
}
