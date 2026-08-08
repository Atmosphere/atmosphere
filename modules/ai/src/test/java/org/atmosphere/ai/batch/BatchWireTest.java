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
package org.atmosphere.ai.batch;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BatchWireTest {

    @Test
    void validSubmissionParsesWithExplicitAndDefaultedCustomIds() {
        var submission = BatchWire.parse("""
                {"agent":"demo","submitter":"ci",
                 "items":[{"custom_id":"a","input":"one"},{"input":"two"}]}""", 10);
        assertEquals("demo", submission.agent());
        assertEquals("ci", submission.submitter());
        assertEquals(2, submission.items().size());
        assertEquals("a", submission.items().get(0).customId());
        assertEquals("one", submission.items().get(0).input());
        // Absent custom_id defaults to the item index.
        assertEquals("1", submission.items().get(1).customId());
    }

    @Test
    void malformedSubmissionsRaise400Envelopes() {
        assertEquals(400, assertThrows(BatchError.class,
                () -> BatchWire.parse("not json {{{", 10)).status());
        assertEquals(400, assertThrows(BatchError.class,
                () -> BatchWire.parse("[1,2]", 10)).status());
        assertEquals("agent", assertThrows(BatchError.class,
                () -> BatchWire.parse("{\"items\":[{\"input\":\"x\"}]}", 10)).param());
        assertEquals("items", assertThrows(BatchError.class,
                () -> BatchWire.parse("{\"agent\":\"a\",\"items\":[]}", 10)).param());
        assertEquals(400, assertThrows(BatchError.class, () -> BatchWire.parse(
                "{\"agent\":\"a\",\"items\":[{\"input\":42}]}", 10)).status());
        assertEquals(400, assertThrows(BatchError.class, () -> BatchWire.parse(
                "{\"agent\":\"a\",\"items\":[{\"input\":\"\"}]}", 10)).status());
        assertEquals(400, assertThrows(BatchError.class, () -> BatchWire.parse(
                "{\"agent\":\"a\",\"items\":[\"bare string\"]}", 10)).status());
        var duplicate = assertThrows(BatchError.class, () -> BatchWire.parse(
                "{\"agent\":\"a\",\"items\":[{\"custom_id\":\"x\",\"input\":\"1\"},"
                        + "{\"custom_id\":\"x\",\"input\":\"2\"}]}", 10));
        assertEquals(400, duplicate.status());
    }

    @Test
    void itemCountAboveTheBoundRaises429NotA400() {
        var over = assertThrows(BatchError.class, () -> BatchWire.parse(
                "{\"agent\":\"a\",\"items\":[{\"input\":\"1\"},{\"input\":\"2\"},"
                        + "{\"input\":\"3\"}]}", 2));
        assertEquals(429, over.status());
        assertEquals("over_capacity", over.code());
        assertEquals(BatchError.TYPE_RATE_LIMIT, over.type());
    }
}
