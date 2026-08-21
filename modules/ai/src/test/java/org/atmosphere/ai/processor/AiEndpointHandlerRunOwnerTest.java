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
package org.atmosphere.ai.processor;

import org.atmosphere.ai.resume.RunReattachSupport;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.security.Principal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression for the run-owner resolution on the registration path.
 * Servlet containers may throw ISE from {@code getUserPrincipal()} when
 * the principal is not yet bound to the dispatching thread — routine
 * under Atmosphere's async/virtual-thread dispatch. The failing
 * behaviour was to swallow that throw at TRACE and register the run as
 * owner {@code "anonymous"}, which the reattach ownership check waves
 * through unconditionally: in an authenticated deployment any caller
 * holding the runId could replay another user's stream. The throw must
 * instead register the fail-closed
 * {@link RunReattachSupport#UNRESOLVED} owner.
 */
class AiEndpointHandlerRunOwnerTest {

    @Test
    void throwingPrincipalRegistersFailClosedUnresolvedOwnerNotAnonymous() {
        var resource = Mockito.mock(AtmosphereResource.class);
        var request = Mockito.mock(AtmosphereRequest.class);
        when(resource.getRequest()).thenReturn(request);
        when(resource.uuid()).thenReturn("res-ise");
        when(request.getAttribute("ai.userId")).thenReturn(null);
        // The exact container behaviour the bug swallowed.
        when(request.getUserPrincipal())
                .thenThrow(new IllegalStateException("principal not bound to thread"));

        assertEquals(RunReattachSupport.UNRESOLVED,
                AiEndpointHandler.resolveRunOwner(resource),
                "a thrown getUserPrincipal means ownership is indeterminate — "
                + "registering as \"anonymous\" would disable the reattach "
                + "ownership check for an authenticated run");
    }

    @Test
    void nullPrincipalStillRegistersAnonymousOwner() {
        var resource = Mockito.mock(AtmosphereResource.class);
        var request = Mockito.mock(AtmosphereRequest.class);
        when(resource.getRequest()).thenReturn(request);
        when(request.getAttribute("ai.userId")).thenReturn(null);
        when(request.getUserPrincipal()).thenReturn(null);

        assertEquals("anonymous", AiEndpointHandler.resolveRunOwner(resource),
                "a cleanly null principal means no auth is configured — the "
                + "open-mode anonymous carve-out must keep working");
    }

    @Test
    void resolvedPrincipalBecomesOwnerAndDefaultsTheUserIdAttribute() {
        var resource = Mockito.mock(AtmosphereResource.class);
        var request = Mockito.mock(AtmosphereRequest.class);
        var principal = Mockito.mock(Principal.class);
        when(resource.getRequest()).thenReturn(request);
        when(request.getAttribute("ai.userId")).thenReturn(null, "alice");
        when(request.getUserPrincipal()).thenReturn(principal);
        when(principal.getName()).thenReturn("alice");

        assertEquals("alice", AiEndpointHandler.resolveRunOwner(resource));
        verify(request).setAttribute("ai.userId", "alice");
    }

    @Test
    void upstreamUserIdAttributeWinsWithoutTouchingThePrincipal() {
        var resource = Mockito.mock(AtmosphereResource.class);
        var request = Mockito.mock(AtmosphereRequest.class);
        when(resource.getRequest()).thenReturn(request);
        when(request.getAttribute("ai.userId")).thenReturn("bob");
        // Even a throwing container must not matter when an auth
        // interceptor already resolved the identity.
        when(request.getUserPrincipal())
                .thenThrow(new IllegalStateException("principal not bound to thread"));

        assertEquals("bob", AiEndpointHandler.resolveRunOwner(resource));
    }
}
