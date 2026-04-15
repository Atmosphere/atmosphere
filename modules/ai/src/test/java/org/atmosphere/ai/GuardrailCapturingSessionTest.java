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
package org.atmosphere.ai;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GuardrailCapturingSessionTest {

    private StreamingSession mockDelegate() {
        return Mockito.mock(StreamingSession.class);
    }

    private AiGuardrail passingGuardrail() {
        var g = Mockito.mock(AiGuardrail.class);
        when(g.inspectResponse(any())).thenReturn(new AiGuardrail.GuardrailResult.Pass());
        return g;
    }

    private AiGuardrail blockingGuardrail(String reason) {
        var g = Mockito.mock(AiGuardrail.class);
        when(g.inspectResponse(any())).thenReturn(new AiGuardrail.GuardrailResult.Block(reason));
        return g;
    }

    @Test
    void sessionIdDelegatesToWrapped() {
        var delegate = mockDelegate();
        when(delegate.sessionId()).thenReturn("test-id");
        var session = new GuardrailCapturingSession(delegate, List.of(), 10);
        assertTrue(session.sessionId().equals("test-id"));
    }

    @Test
    void sendForwardsWhenGuardrailPasses() {
        var delegate = mockDelegate();
        var session = new GuardrailCapturingSession(delegate, List.of(passingGuardrail()), 5);
        session.send("hello");
        verify(delegate).send("hello");
    }

    @Test
    void sendBlocksWhenGuardrailBlocks() {
        var delegate = mockDelegate();
        // checkInterval=1 so it checks every character
        var session = new GuardrailCapturingSession(delegate, List.of(blockingGuardrail("unsafe")), 1);
        session.send("bad content");
        // Should have called error on delegate
        verify(delegate).error(any(SecurityException.class));
    }

    @Test
    void completeForwardsWhenNotBlocked() {
        var delegate = mockDelegate();
        var session = new GuardrailCapturingSession(delegate, List.of(passingGuardrail()), 100);
        session.send("safe");
        session.complete();
        verify(delegate).complete();
    }

    @Test
    void isClosedReturnsTrueWhenBlocked() {
        var delegate = mockDelegate();
        when(delegate.isClosed()).thenReturn(false);
        var session = new GuardrailCapturingSession(delegate, List.of(blockingGuardrail("bad")), 1);
        session.send("x");
        assertTrue(session.isClosed());
    }

    @Test
    void isClosedDelegatesToWrappedWhenNotBlocked() {
        var delegate = mockDelegate();
        when(delegate.isClosed()).thenReturn(true);
        var session = new GuardrailCapturingSession(delegate, List.of(), 100);
        assertTrue(session.isClosed());
    }

    @Test
    void errorForwardsToDelegateDirectly() {
        var delegate = mockDelegate();
        var ex = new RuntimeException("test");
        var session = new GuardrailCapturingSession(delegate, List.of(), 100);
        session.error(ex);
        verify(delegate).error(ex);
    }

    @Test
    void sendMetadataForwardsToDelegateDirectly() {
        var delegate = mockDelegate();
        var session = new GuardrailCapturingSession(delegate, List.of(), 100);
        session.sendMetadata("key", "value");
        verify(delegate).sendMetadata("key", "value");
    }

    @Test
    void progressForwardsToDelegateDirectly() {
        var delegate = mockDelegate();
        var session = new GuardrailCapturingSession(delegate, List.of(), 100);
        session.progress("loading...");
        verify(delegate).progress("loading...");
    }

    @Test
    void blockedSessionDropsSubsequentSends() {
        var delegate = mockDelegate();
        var session = new GuardrailCapturingSession(delegate, List.of(blockingGuardrail("no")), 1);
        session.send("first");
        // After blocking, further sends should be dropped
        session.send("second");
        verify(delegate, never()).send("second");
    }

    @Test
    void completeWithSummaryForwardsWhenNotBlocked() {
        var delegate = mockDelegate();
        var session = new GuardrailCapturingSession(delegate, List.of(passingGuardrail()), 100);
        session.complete("summary text");
        verify(delegate).complete("summary text");
    }
}
