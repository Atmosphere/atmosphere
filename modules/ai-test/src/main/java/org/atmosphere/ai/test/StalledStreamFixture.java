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
package org.atmosphere.ai.test;

import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.tool.ToolDefinition;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * A runtime pointed at a {@link StallingHttpServer}, for the stream idle
 * contract of {@link AbstractAgentRuntimeContractTest}. It must say what
 * happens to the stalled connection when the turn ends: {@link Pending#releases()}
 * (the runtime closes it, and the contract checks the server saw it close), or
 * {@link Pending#leftToProvider(String)} with the reason it cannot (the contract
 * then logs the residual and skips that check, never a silent pass).
 *
 * <p>The runtime must not be attached to an environment: the contract attaches
 * its own, with the bound it tests.</p>
 */
public final class StalledStreamFixture {

    private final AgentRuntime runtime;
    private final Function<List<ToolDefinition>, AgentExecutionContext> context;
    private final boolean releases;
    private final String residual;
    private final boolean toolTurns;
    private final String beforeHeadersResidual;
    private final String keepaliveResidual;
    private final String unaryResidual;

    private StalledStreamFixture(AgentRuntime runtime,
                                 Function<List<ToolDefinition>, AgentExecutionContext> context,
                                 boolean releases, String residual, boolean toolTurns,
                                 String beforeHeadersResidual, String keepaliveResidual) {
        this(runtime, context, releases, residual, toolTurns, beforeHeadersResidual, keepaliveResidual, null);
    }

    private StalledStreamFixture(AgentRuntime runtime,
                                 Function<List<ToolDefinition>, AgentExecutionContext> context,
                                 boolean releases, String residual, boolean toolTurns,
                                 String beforeHeadersResidual, String keepaliveResidual, String unaryResidual) {
        this.runtime = runtime;
        this.context = context;
        this.releases = releases;
        this.residual = residual;
        this.toolTurns = toolTurns;
        this.beforeHeadersResidual = beforeHeadersResidual;
        this.keepaliveResidual = keepaliveResidual;
        this.unaryResidual = unaryResidual;
    }

    /**
     * Starts a fixture.
     *
     * @param runtime a runtime whose model is the server's {@link StallingHttpServer#baseUrl()};
     *                not attached to any environment
     * @param context builds the turn's context carrying the given tools (empty
     *                for a text turn)
     * @return the fixture, once it says whether the connection is released
     */
    public static Pending of(AgentRuntime runtime,
                             Function<List<ToolDefinition>, AgentExecutionContext> context) {
        return new Pending(Objects.requireNonNull(runtime, "runtime"),
                Objects.requireNonNull(context, "context"));
    }

    /**
     * Opts in to the tool and approval cases: the runtime runs a tool the
     * server's first answer calls through
     * {@link org.atmosphere.ai.tool.ToolExecutionHelper}, and sends the next
     * round to the same server.
     *
     * @return a copy with the tool cases on
     */
    public StalledStreamFixture withToolTurns() {
        return new StalledStreamFixture(runtime, context, releases, residual, true, beforeHeadersResidual,
                keepaliveResidual, unaryResidual);
    }

    /**
     * Declares that a connection that stalls before its response headers is
     * not released when the turn ends, though one that stalls after them is
     * (the runtime's abort handle arrives with the first event, for one).
     *
     * @param reason why the connection is left to the provider before its headers
     * @return a copy declaring it
     */
    public StalledStreamFixture notReleasedBeforeHeaders(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("notReleasedBeforeHeaders needs the reason");
        }
        return new StalledStreamFixture(runtime, context, releases, residual, toolTurns, reason,
                keepaliveResidual, unaryResidual);
    }

    /**
     * Declares that the runtime cannot see the provider's keep-alives (its
     * client drops them before any callback the runtime has), so a provider
     * that sends only keep-alives for longer than the bound times the turn out.
     *
     * @param reason why the keep-alives are invisible
     * @return a copy declaring it
     */
    public StalledStreamFixture keepalivesNotSeen(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("keepalivesNotSeen needs the reason");
        }
        return new StalledStreamFixture(runtime, context, releases, residual, toolTurns, beforeHeadersResidual,
                reason, unaryResidual);
    }

    /**
     * Declares that the runtime's model call is not streamed: the model's
     * answer reaches the session only once it is complete, so no chunk of a
     * stalled answer ever does. The cases still check that the turn ends once,
     * that a cancel settles and that the connection is released; only the wait
     * for the stalled answer's first chunk is skipped, and logged.
     *
     * @param reason why the call is not streamed
     * @return a copy declaring it
     */
    public StalledStreamFixture unaryModelCall(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("unaryModelCall needs the reason");
        }
        return new StalledStreamFixture(runtime, context, releases, residual, toolTurns, beforeHeadersResidual,
                keepaliveResidual, reason);
    }

    /** @return why the model call is not streamed, or {@code null} when it is */
    public String unaryResidual() {
        return unaryResidual;
    }

    /** @return why the runtime cannot see keep-alives, or {@code null} when it does */
    public String keepaliveResidual() {
        return keepaliveResidual;
    }

    public AgentRuntime runtime() {
        return runtime;
    }

    /**
     * @param tools the tools of the turn
     * @return a context for a turn carrying {@code tools}
     */
    public AgentExecutionContext context(List<ToolDefinition> tools) {
        return context.apply(tools);
    }

    /** @return whether ending the turn closes the stalled connection */
    public boolean releases() {
        return releases;
    }

    /** @return why the connection is left to the provider, when it is; else {@code null} */
    public String residual() {
        return residual;
    }

    /**
     * @return why a connection that stalls before its headers is left to the
     *         provider, or {@code null} when it is released like any other
     *         (or when nothing is released: see {@link #residual()})
     */
    public String beforeHeadersResidual() {
        return releases ? beforeHeadersResidual : residual;
    }

    /** @return whether the tool and approval cases run */
    public boolean toolTurns() {
        return toolTurns;
    }

    /** A fixture that has not said yet whether it releases the connection. */
    public static final class Pending {
        private final AgentRuntime runtime;
        private final Function<List<ToolDefinition>, AgentExecutionContext> context;

        private Pending(AgentRuntime runtime, Function<List<ToolDefinition>, AgentExecutionContext> context) {
            this.runtime = runtime;
            this.context = context;
        }

        /** @return a fixture whose runtime closes the stalled connection when the turn ends */
        public StalledStreamFixture releases() {
            return new StalledStreamFixture(runtime, context, true, null, false, null, null);
        }

        /**
         * @param reason why the runtime cannot close the connection (an upstream
         *               call with no abort, a client the application owns)
         * @return a fixture whose connection outlives the turn, for the stated reason
         */
        public StalledStreamFixture leftToProvider(String reason) {
            if (reason == null || reason.isBlank()) {
                throw new IllegalArgumentException("leftToProvider needs the reason the connection is not released");
            }
            return new StalledStreamFixture(runtime, context, false, reason, false, null, null);
        }
    }
}
