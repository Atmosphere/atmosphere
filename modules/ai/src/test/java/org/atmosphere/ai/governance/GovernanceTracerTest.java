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
package org.atmosphere.ai.governance;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class GovernanceTracerTest {

    @Test
    void endRecordsExceptionAndIsIdempotent() {
        var span = new FakeSpan();
        var error = new IllegalStateException("boom");
        var handle = GovernanceTracer.Handle.forSpan(span);

        handle.end("error", "failed", error);
        handle.end("admit", "ignored");

        assertEquals(1, span.endCount);
        assertEquals("policy.decision=error", span.attributes.get(0));
        assertEquals("policy.reason=failed", span.attributes.get(1));
        assertSame(error, span.errors.get(0));
    }

    static final class FakeSpan {
        final List<String> attributes = new ArrayList<>();
        final List<Throwable> errors = new ArrayList<>();
        int endCount;

        public FakeSpan setAttribute(String key, String value) {
            attributes.add(key + "=" + value);
            return this;
        }

        public FakeSpan recordException(Throwable error) {
            errors.add(error);
            return this;
        }

        public void end() {
            endCount++;
        }
    }
}
