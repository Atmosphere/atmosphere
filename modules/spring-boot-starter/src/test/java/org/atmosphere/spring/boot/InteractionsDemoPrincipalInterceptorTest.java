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
package org.atmosphere.spring.boot;

import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression coverage for the interactions live-stream principal fix.
 *
 * <p>The demo principal reaches the REST endpoints via a servlet {@code Filter},
 * but a filter-set request attribute does not survive Atmosphere's WebSocket
 * upgrade — so the live-stream socket resolved {@code anonymous} and ownership
 * failed closed. Stamping {@code ai.userId} from an {@code AtmosphereInterceptor}
 * (which runs for every transport) fixes it. These tests pin that behavior.</p>
 */
class InteractionsDemoPrincipalInterceptorTest {

    @Test
    void stampsDemoPrincipalWhenAbsent() {
        var request = mock(AtmosphereRequest.class);
        var resource = mock(AtmosphereResource.class);
        when(resource.getRequest()).thenReturn(request);
        when(request.getAttribute("ai.userId")).thenReturn(null);

        var action = new InteractionsDemoPrincipalInterceptor("demo-user").inspect(resource);

        verify(request).setAttribute("ai.userId", "demo-user");
        assertThat(action.type()).isEqualTo(Action.TYPE.CONTINUE);
    }

    @Test
    void doesNotClobberAnExistingPrincipal() {
        var request = mock(AtmosphereRequest.class);
        var resource = mock(AtmosphereResource.class);
        when(resource.getRequest()).thenReturn(request);
        when(request.getAttribute("ai.userId")).thenReturn("real-user");

        var action = new InteractionsDemoPrincipalInterceptor("demo-user").inspect(resource);

        verify(request, never()).setAttribute(org.mockito.ArgumentMatchers.eq("ai.userId"),
                org.mockito.ArgumentMatchers.any());
        assertThat(action.type()).isEqualTo(Action.TYPE.CONTINUE);
    }

    @Test
    void toleratesNullRequest() {
        var resource = mock(AtmosphereResource.class);
        when(resource.getRequest()).thenReturn(null);

        var action = new InteractionsDemoPrincipalInterceptor("demo-user").inspect(resource);

        assertThat(action.type()).isEqualTo(Action.TYPE.CONTINUE);
    }
}
