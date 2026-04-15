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
package org.atmosphere.cpr;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class BroadcastFilterAdapterTest {

    private final BroadcastFilterAdapter adapter = new BroadcastFilterAdapter();

    @Test
    void filterWithoutResourceReturnsContinueAction() {
        BroadcastFilter.BroadcastAction action = adapter.filter("broadcaster-1", "original", "message");
        assertNotNull(action);
        assertEquals(BroadcastFilter.BroadcastAction.ACTION.CONTINUE, action.action());
        assertEquals("message", action.message());
    }

    @Test
    void filterWithResourceReturnsContinueAction() {
        AtmosphereResource resource = Mockito.mock(AtmosphereResource.class);
        BroadcastFilter.BroadcastAction action = adapter.filter("broadcaster-1", resource, "original", "transformed");
        assertNotNull(action);
        assertEquals(BroadcastFilter.BroadcastAction.ACTION.CONTINUE, action.action());
        assertEquals("transformed", action.message());
    }

    @Test
    void filterPreservesMessageObject() {
        Object msg = Integer.valueOf(42);
        BroadcastFilter.BroadcastAction action = adapter.filter("b", "orig", msg);
        assertEquals(msg, action.message());
    }

    @Test
    void filterPerRequestPreservesMessageObject() {
        AtmosphereResource resource = Mockito.mock(AtmosphereResource.class);
        Object msg = Integer.valueOf(99);
        BroadcastFilter.BroadcastAction action = adapter.filter("b", resource, "orig", msg);
        assertEquals(msg, action.message());
    }
}
