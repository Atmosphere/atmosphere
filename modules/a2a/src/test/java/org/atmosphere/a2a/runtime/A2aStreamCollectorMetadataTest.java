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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Regression: {@code sendMetadata} on the A2A stream collector used to be
 * an empty body (the drop was even mispointed at registre#11), so response
 * metadata — model used, cache hit, budget degradation — never reached A2A
 * clients even though Task and TaskStatusUpdateEvent both carry metadata
 * maps. Metadata must land on the task.
 */
class A2aStreamCollectorMetadataTest {

    @Test
    void responseMetadataLandsOnTheTask() {
        var ctx = new TaskContext("task-1", "ctx-1");
        var collector = new A2aStreamCollector(ctx, null);

        collector.sendMetadata("ai.model.used", "claude-sonnet-5");
        collector.sendMetadata("ai.cache.hit", true);
        collector.sendMetadata(null, "ignored");
        collector.sendMetadata("null.value", null);

        assertEquals("claude-sonnet-5", ctx.metadata().get("ai.model.used"),
                "the A2A client must learn which model answered");
        assertEquals(true, ctx.metadata().get("ai.cache.hit"));
        assertEquals("", ctx.metadata().get("null.value"),
                "null values are normalized, not dropped");
        assertFalse(ctx.metadata().containsKey("null"),
                "a null key is ignored: " + ctx.metadata());
    }
}
