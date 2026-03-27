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
package org.atmosphere.coordinator.evaluation;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvaluationTest {

    @Test
    void passFactory() {
        var eval = Evaluation.pass(0.95, "Looks good");
        assertTrue(eval.passed());
        assertEquals(0.95, eval.score());
        assertEquals("Looks good", eval.reason());
        assertTrue(eval.metadata().isEmpty());
    }

    @Test
    void failFactory() {
        var eval = Evaluation.fail(0.2, "Too vague");
        assertFalse(eval.passed());
        assertEquals(0.2, eval.score());
    }

    @Test
    void scoreValidation() {
        assertThrows(IllegalArgumentException.class, () -> new Evaluation(-0.1, true, "bad", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new Evaluation(1.1, true, "bad", Map.of()));
    }

    @Test
    void boundaryScoresAllowed() {
        assertNotNull(new Evaluation(0.0, false, "zero", Map.of()));
        assertNotNull(new Evaluation(1.0, true, "perfect", Map.of()));
    }

    @Test
    void metadataDefensivelyCopied() {
        var meta = new java.util.HashMap<String, Object>();
        meta.put("key", "value");
        var eval = new Evaluation(0.5, true, "ok", meta);

        assertEquals(Map.of("key", "value"), eval.metadata());
    }

    @Test
    void nullMetadataBecomesEmpty() {
        var eval = new Evaluation(0.5, true, "ok", null);
        assertNotNull(eval.metadata());
        assertTrue(eval.metadata().isEmpty());
    }
}
