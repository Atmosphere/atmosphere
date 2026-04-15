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

import org.atmosphere.interceptor.InvokationOrder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class AtmosphereInterceptorAdapterTest {

    private AtmosphereInterceptorAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new AtmosphereInterceptorAdapter() {
        };
    }

    @Test
    void configureDoesNotThrow() {
        AtmosphereConfig config = Mockito.mock(AtmosphereConfig.class);
        assertDoesNotThrow(() -> adapter.configure(config));
    }

    @Test
    void inspectSetsAsyncIOWriterWhenNull() {
        AtmosphereResource resource = Mockito.mock(AtmosphereResource.class);
        AtmosphereResponse response = Mockito.mock(AtmosphereResponse.class);
        Mockito.when(resource.getResponse()).thenReturn(response);
        Mockito.when(response.getAsyncIOWriter()).thenReturn(null);

        Action action = adapter.inspect(resource);

        assertEquals(Action.TYPE.CONTINUE, action.type());
        Mockito.verify(response).asyncIOWriter(Mockito.any(AtmosphereInterceptorWriter.class));
    }

    @Test
    void inspectDoesNotOverrideExistingWriter() {
        AtmosphereResource resource = Mockito.mock(AtmosphereResource.class);
        AtmosphereResponse response = Mockito.mock(AtmosphereResponse.class);
        AsyncIOWriter existingWriter = Mockito.mock(AsyncIOWriter.class);
        Mockito.when(resource.getResponse()).thenReturn(response);
        Mockito.when(response.getAsyncIOWriter()).thenReturn(existingWriter);

        Action action = adapter.inspect(resource);

        assertEquals(Action.TYPE.CONTINUE, action.type());
        Mockito.verify(response, Mockito.never()).asyncIOWriter(Mockito.any());
    }

    @Test
    void inspectReturnsContinue() {
        AtmosphereResource resource = Mockito.mock(AtmosphereResource.class);
        AtmosphereResponse response = Mockito.mock(AtmosphereResponse.class);
        Mockito.when(resource.getResponse()).thenReturn(response);
        Mockito.when(response.getAsyncIOWriter()).thenReturn(Mockito.mock(AsyncIOWriter.class));

        Action result = adapter.inspect(resource);
        assertNotNull(result);
        assertEquals(Action.TYPE.CONTINUE, result.type());
    }

    @Test
    void postInspectDoesNotThrow() {
        AtmosphereResource resource = Mockito.mock(AtmosphereResource.class);
        assertDoesNotThrow(() -> adapter.postInspect(resource));
    }

    @Test
    void destroyDoesNotThrow() {
        assertDoesNotThrow(() -> adapter.destroy());
    }

    @Test
    void priorityReturnsAfterDefault() {
        assertEquals(InvokationOrder.AFTER_DEFAULT, adapter.priority());
    }

    @Test
    void toStringReturnsClassName() {
        String name = adapter.toString();
        assertNotNull(name);
    }

    @Test
    void implementsInvokationOrder() {
        assertInstanceOf(InvokationOrder.class, adapter);
    }
}
