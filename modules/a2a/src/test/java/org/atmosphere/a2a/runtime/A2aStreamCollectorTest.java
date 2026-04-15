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
package org.atmosphere.a2a.runtime;

import org.atmosphere.a2a.types.TaskState;
import org.atmosphere.ai.AiPipeline;
import org.atmosphere.ai.StreamingSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class A2aStreamCollectorTest {

    private TaskContext taskCtx;
    private AiPipeline pipeline;

    @BeforeEach
    void setUp() {
        taskCtx = mock(TaskContext.class);
        pipeline = mock(AiPipeline.class);
        when(taskCtx.taskId()).thenReturn("task-123");
    }

    @Test
    void sessionIdDelegatesToTaskId() {
        var collector = new A2aStreamCollector(taskCtx, null);

        assertEquals("task-123", collector.sessionId());
        verify(taskCtx).taskId();
    }

    @Test
    void sendAppendsToBuffer() {
        var collector = new A2aStreamCollector(taskCtx, null);

        collector.send("hello ");
        collector.send("world");

        assertEquals("hello world", collector.buffer());
    }

    @Test
    void streamWithNullPipelineAppendsToBuffer() {
        var collector = new A2aStreamCollector(taskCtx, null);

        collector.stream("streamed text");

        assertEquals("streamed text", collector.buffer());
    }

    @Test
    void streamWithPipelineDelegatesToExecute() {
        doNothing().when(pipeline).execute(anyString(), anyString(), any(StreamingSession.class));

        var collector = new A2aStreamCollector(taskCtx, pipeline);

        collector.stream("prompt");

        verify(pipeline).execute(eq("task-123"), eq("prompt"), eq(collector));
    }

    @Test
    void sendMetadataIsNoOp() {
        var collector = new A2aStreamCollector(taskCtx, null);

        // Should not throw or produce any side effect
        collector.sendMetadata("key", "value");

        assertEquals("", collector.buffer());
    }

    @Test
    void progressUpdatesTaskStatus() {
        var collector = new A2aStreamCollector(taskCtx, null);

        collector.progress("step 1 done");

        verify(taskCtx).updateStatus(TaskState.WORKING, "step 1 done");
    }

    @Test
    void completeFinalizesTask() {
        var collector = new A2aStreamCollector(taskCtx, null);
        collector.send("result");

        collector.complete();

        verify(taskCtx).complete("result");
    }

    @Test
    void completeIsIdempotent() {
        var collector = new A2aStreamCollector(taskCtx, null);
        collector.send("result");

        collector.complete();
        collector.complete();

        // complete on taskCtx should only be called once
        verify(taskCtx).complete("result");
    }

    @Test
    void completeWithSummaryUsesSummary() {
        var collector = new A2aStreamCollector(taskCtx, null);
        collector.send("buffered");

        collector.complete("custom summary");

        verify(taskCtx).complete("custom summary");
    }

    @Test
    void completeWithNullSummaryUsesBuffer() {
        var collector = new A2aStreamCollector(taskCtx, null);
        collector.send("buffered data");

        collector.complete(null);

        verify(taskCtx).complete("buffered data");
    }

    @Test
    void errorSetsErroredFlag() {
        var collector = new A2aStreamCollector(taskCtx, null);

        collector.error(new RuntimeException("fail"));

        assertEquals(true, collector.hasErrored());
    }

    @Test
    void errorFinalizesTask() {
        var collector = new A2aStreamCollector(taskCtx, null);

        collector.error(new RuntimeException("something broke"));

        verify(taskCtx).fail("something broke");
    }

    @Test
    void isClosedAfterComplete() {
        var collector = new A2aStreamCollector(taskCtx, null);

        assertFalse(collector.isClosed());

        collector.complete();

        assertEquals(true, collector.isClosed());
    }

    @Test
    void isClosedAfterError() {
        var collector = new A2aStreamCollector(taskCtx, null);

        collector.error(new RuntimeException("err"));

        assertEquals(true, collector.isClosed());
    }

    @Test
    void hasErroredFalseInitially() {
        var collector = new A2aStreamCollector(taskCtx, null);

        assertFalse(collector.hasErrored());
    }

    @Test
    void awaitAndFinalizeAutoCompletesIfNotFinalized() {
        var collector = new A2aStreamCollector(taskCtx, null);
        collector.send("auto-result");

        collector.awaitAndFinalize(50);

        verify(taskCtx).complete("auto-result");
        assertEquals(true, collector.isClosed());
    }

    @Test
    void awaitAndFinalizeNoOpIfAlreadyCompleted() {
        var collector = new A2aStreamCollector(taskCtx, null);
        collector.send("done");
        collector.complete();

        collector.awaitAndFinalize(50);

        // complete on taskCtx should only be called once (from complete(), not awaitAndFinalize)
        verify(taskCtx).complete("done");
    }

    @Test
    void errorAfterCompleteDoesNotCallFailAgain() {
        var collector = new A2aStreamCollector(taskCtx, null);
        collector.complete();

        collector.error(new RuntimeException("late error"));

        verify(taskCtx, never()).fail(anyString());
        assertEquals(true, collector.hasErrored());
    }

    @Test
    void bufferInitiallyEmpty() {
        var collector = new A2aStreamCollector(taskCtx, null);

        assertNotNull(collector.buffer());
        assertEquals("", collector.buffer());
    }
}
