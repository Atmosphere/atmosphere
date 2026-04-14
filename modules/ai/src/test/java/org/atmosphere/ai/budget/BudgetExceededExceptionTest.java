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
package org.atmosphere.ai.budget;

import org.atmosphere.ai.AiException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BudgetExceededExceptionTest {

    @Test
    void fieldsAreRetained() {
        var ex = new BudgetExceededException("user-1", 1000, 1500);
        assertEquals("user-1", ex.ownerId());
        assertEquals(1000, ex.budget());
        assertEquals(1500, ex.used());
    }

    @Test
    void messageContainsOwnerAndCounts() {
        var ex = new BudgetExceededException("org-42", 500, 750);
        assertTrue(ex.getMessage().contains("org-42"));
        assertTrue(ex.getMessage().contains("500"));
        assertTrue(ex.getMessage().contains("750"));
    }

    @Test
    void extendsAiException() {
        var ex = new BudgetExceededException("x", 1, 2);
        assertTrue(ex instanceof AiException);
    }
}
