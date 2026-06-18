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
package org.atmosphere.samples.springboot.browseragent;

import org.atmosphere.ai.StreamingSession;
import org.atmosphere.cpr.AtmosphereResource;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression test for the browser-agent Cohere-key guard.
 *
 * <p>This sample pins the Cohere runtime. The generic key resolver falls back
 * to {@code LLM_API_KEY} / {@code OPENAI_API_KEY} / {@code GEMINI_API_KEY}, so
 * before the fix a stray non-Cohere key was handed to Cohere and produced a
 * confusing {@code 401 Incorrect API key}. The fix checks {@code COHERE_API_KEY}
 * directly and, when absent, surfaces a clear hint instead of calling the
 * runtime.</p>
 *
 * <p>The key is read through the package-private {@link BrowserAgent#cohereApiKey()}
 * seam ({@code System.getenv} can't be set in-process), so these tests drive the
 * no-key and with-key branches deterministically.</p>
 */
class BrowserAgentKeyGuardTest {

    private AtmosphereResource mockResource() {
        var resource = mock(AtmosphereResource.class);
        when(resource.uuid()).thenReturn("test-uuid");
        return resource;
    }

    @Test
    void withoutCohereKeyShowsHintAndDoesNotCallRuntime() {
        var agent = new BrowserAgent() {
            @Override
            String cohereApiKey() {
                return null;   // simulate COHERE_API_KEY unset
            }
        };
        var session = mock(StreamingSession.class);

        agent.onPrompt("What's the top story on news.ycombinator.com?", session, mockResource());

        // A clear "set COHERE_API_KEY" hint is rendered...
        verify(session).send(contains("COHERE_API_KEY"));
        verify(session).complete();
        // ...and we must NOT delegate to the (Cohere) runtime, which is where
        // the leaked-key 401 originated.
        verify(session, never()).stream(anyString());
    }

    @Test
    void withCohereKeyDelegatesToRuntime() {
        var agent = new BrowserAgent() {
            @Override
            String cohereApiKey() {
                return "co-test-key";   // a Cohere key is present
            }
        };
        var session = mock(StreamingSession.class);

        agent.onPrompt("browse something", session, mockResource());

        // With a key, the agent delegates the full loop to the runtime and
        // does not short-circuit with the hint.
        verify(session).stream("browse something");
        verify(session, never()).send(contains("COHERE_API_KEY"));
    }
}
