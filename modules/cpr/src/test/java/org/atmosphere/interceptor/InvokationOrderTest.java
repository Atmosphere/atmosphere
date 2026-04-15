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
package org.atmosphere.interceptor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class InvokationOrderTest {

    @Test
    void priorityEnumHasThreeValues() {
        assertEquals(3, InvokationOrder.PRIORITY.values().length);
    }

    @Test
    void priorityAfterDefaultExists() {
        assertNotNull(InvokationOrder.PRIORITY.AFTER_DEFAULT);
    }

    @Test
    void priorityBeforeDefaultExists() {
        assertNotNull(InvokationOrder.PRIORITY.BEFORE_DEFAULT);
    }

    @Test
    void priorityFirstBeforeDefaultExists() {
        assertNotNull(InvokationOrder.PRIORITY.FIRST_BEFORE_DEFAULT);
    }

    @Test
    void interfaceConstantMatchesEnumValue() {
        assertSame(InvokationOrder.PRIORITY.AFTER_DEFAULT, InvokationOrder.AFTER_DEFAULT);
        assertSame(InvokationOrder.PRIORITY.BEFORE_DEFAULT, InvokationOrder.BEFORE_DEFAULT);
        assertSame(InvokationOrder.PRIORITY.FIRST_BEFORE_DEFAULT, InvokationOrder.FIRST_BEFORE_DEFAULT);
    }

    @Test
    void valueOfRoundTrips() {
        for (InvokationOrder.PRIORITY p : InvokationOrder.PRIORITY.values()) {
            assertEquals(p, InvokationOrder.PRIORITY.valueOf(p.name()));
        }
    }

    @Test
    void implementingClassCanReturnPriority() {
        InvokationOrder order = () -> InvokationOrder.PRIORITY.BEFORE_DEFAULT;
        assertEquals(InvokationOrder.PRIORITY.BEFORE_DEFAULT, order.priority());
    }
}
