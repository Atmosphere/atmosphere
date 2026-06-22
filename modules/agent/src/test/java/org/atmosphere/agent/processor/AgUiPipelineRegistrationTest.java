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
package org.atmosphere.agent.processor;

import org.atmosphere.agui.runtime.AgUiHandler;
import org.atmosphere.ai.AiPipeline;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.Prompt;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereHandler;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Regression tests for {@link AgentProcessor#registerAgUi}: a {@code @Agent}
 * whose {@code @Prompt(String, StreamingSession)} method calls
 * {@code session.stream(message)} must register an {@link AgUiHandler} at
 * {@code /atmosphere/agent/{name}/agui} <em>with a non-null
 * {@link AiPipeline}</em>.
 *
 * <p>A null pipeline is the exact condition under which
 * {@code AgUiHandler.ResourceAgUiStreamingSession.stream(String)} throws
 * {@link UnsupportedOperationException} ("stream(String) requires an
 * AiPipeline"), which would silently break every real AG-UI agent. Pinning the
 * non-null pipeline here makes that drift a build failure.</p>
 */
class AgUiPipelineRegistrationTest {

    /** Minimal real-pipeline agent: {@code @Prompt} that streams via the pipeline. */
    static class StreamingAgent {
        @Prompt
        public void onPrompt(String message, StreamingSession session) {
            session.stream(message);
        }
    }

    private static Method promptMethod() throws NoSuchMethodException {
        return StreamingAgent.class.getDeclaredMethod(
                "onPrompt", String.class, StreamingSession.class);
    }

    /**
     * Invoke the private {@code registerAgUi} the same way {@code handle()} does,
     * capturing the handler registered against {@code basePath + "/agui"}.
     */
    private AtmosphereHandler invokeRegisterAgUiAndCapture(AiPipeline pipeline) throws Exception {
        var processor = new AgentProcessor();
        var framework = mock(AtmosphereFramework.class);
        var basePath = "/atmosphere/agent/assistant";

        var register = AgentProcessor.class.getDeclaredMethod(
                "registerAgUi",
                AtmosphereFramework.class, Object.class, Method.class,
                String.class, AiPipeline.class, List.class);
        register.setAccessible(true);

        var target = new StreamingAgent();
        var protocols = new ArrayList<String>();
        register.invoke(processor, framework, target, promptMethod(),
                basePath, pipeline, protocols);

        assertTrue(protocols.contains("ag-ui"),
                "registerAgUi should record the ag-ui protocol");

        ArgumentCaptor<AtmosphereHandler> handlerCaptor =
                ArgumentCaptor.forClass(AtmosphereHandler.class);
        verify(framework).addAtmosphereHandler(
                eq(basePath + "/agui"), handlerCaptor.capture(),
                anyList());
        return handlerCaptor.getValue();
    }

    @Test
    void registerAgUiInstallsHandlerWithNonNullPipeline() throws Exception {
        var pipeline = mock(AiPipeline.class);
        var handler = invokeRegisterAgUiAndCapture(pipeline);

        assertNotNull(handler, "An AG-UI handler must be registered");
        var agUiHandler = assertInstanceOf(AgUiHandler.class, handler,
                "The handler at {basePath}/agui must be an AgUiHandler");

        // The pipeline field must be the non-null pipeline the processor built —
        // a null here is precisely the UnsupportedOperationException regression.
        var pipelineField = AgUiHandler.class.getDeclaredField("pipeline");
        pipelineField.setAccessible(true);
        var wired = pipelineField.get(agUiHandler);
        assertNotNull(wired, "AgUiHandler must be registered WITH a pipeline so "
                + "session.stream(message) does not throw UnsupportedOperationException");
        assertEquals(pipeline, wired, "AgUiHandler must hold the pipeline the processor built");
    }
}
