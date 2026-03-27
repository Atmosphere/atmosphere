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

import org.atmosphere.auth.TokenRefresher;
import org.atmosphere.auth.TokenValidator;
import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.FrameworkConfig;
import org.atmosphere.cpr.HeaderConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

public class AuthInterceptorTest {

    private TokenValidator alwaysValid;
    private TokenValidator alwaysInvalid;
    private TokenValidator alwaysExpired;
    private AuthInterceptor interceptor;

    @BeforeEach
    public void setUp() {
        alwaysValid = token -> new TokenValidator.Valid("user-" + token, Map.of("token", token));
        alwaysInvalid = token -> new TokenValidator.Invalid("rejected");
        alwaysExpired = token -> new TokenValidator.Expired("token expired");
    }

    private AtmosphereResourceImpl mockResource(String uuid, String headerToken, String queryToken) {
        var resource = mock(AtmosphereResourceImpl.class);
        var request = mock(AtmosphereRequest.class);
        var response = mock(AtmosphereResponse.class);

        when(resource.uuid()).thenReturn(uuid);
        when(resource.getRequest()).thenReturn(request);
        when(resource.getResponse()).thenReturn(response);

        // For super.inspect() which accesses the response
        when(response.getAsyncIOWriter()).thenReturn(null);

        // Header token
        when(request.getHeader(HeaderConfig.X_ATMOSPHERE_AUTH)).thenReturn(headerToken);

        // Query param token
        when(request.getParameter(HeaderConfig.X_ATMOSPHERE_AUTH)).thenReturn(queryToken);

        // For Utils.webSocketMessage() — return false by default (no WS message attribute)
        when(resource.getRequest(false)).thenReturn(request);
        when(request.getAttribute(FrameworkConfig.WEBSOCKET_MESSAGE)).thenReturn(null);

        return resource;
    }

    @Test
    public void testValidTokenFromHeader() {
        interceptor = new AuthInterceptor(alwaysValid);

        var resource = mockResource("client1", "my-token", null);
        var action = interceptor.inspect(resource);

        assertEquals(Action.CONTINUE, action);
        verify(resource.getRequest()).setAttribute(eq(FrameworkConfig.AUTH_PRINCIPAL), argThat(p ->
                ((java.security.Principal) p).getName().equals("user-my-token")));
        verify(resource.getRequest()).setAttribute(eq(FrameworkConfig.AUTH_CLAIMS), any());
        assertEquals(1, interceptor.authenticatedCount());
    }

    @Test
    public void testValidTokenFromQueryParam() {
        interceptor = new AuthInterceptor(alwaysValid);

        var resource = mockResource("client2", null, "query-token");
        var action = interceptor.inspect(resource);

        assertEquals(Action.CONTINUE, action);
        verify(resource.getRequest()).setAttribute(eq(FrameworkConfig.AUTH_PRINCIPAL), argThat(p ->
                ((java.security.Principal) p).getName().equals("user-query-token")));
    }

    @Test
    public void testHeaderTokenTakesPrecedenceOverQueryParam() {
        interceptor = new AuthInterceptor(alwaysValid);

        var resource = mockResource("client3", "header-tok", "query-tok");
        interceptor.inspect(resource);

        // Should use header token, not query param
        verify(resource.getRequest()).setAttribute(eq(FrameworkConfig.AUTH_PRINCIPAL), argThat(p ->
                ((java.security.Principal) p).getName().equals("user-header-tok")));
    }

    @Test
    public void testMissingTokenDisconnects() {
        interceptor = new AuthInterceptor(alwaysValid);

        var resource = mockResource("client4", null, null);
        var action = interceptor.inspect(resource);

        assertEquals(Action.CANCELLED, action);
        verify(resource.getResponse()).setStatus(401);
        assertEquals(1, interceptor.totalRejected());
    }

    @Test
    public void testBlankTokenIsRejected() {
        interceptor = new AuthInterceptor(alwaysValid);

        var resource = mockResource("client5", "  ", "");
        var action = interceptor.inspect(resource);

        assertEquals(Action.CANCELLED, action);
        assertEquals(1, interceptor.totalRejected());
    }

    @Test
    public void testInvalidTokenDisconnects() {
        interceptor = new AuthInterceptor(alwaysInvalid);

        var resource = mockResource("client6", "bad-token", null);
        var action = interceptor.inspect(resource);

        assertEquals(Action.CANCELLED, action);
        verify(resource.getResponse()).setStatus(401);
        verify(resource.getResponse()).setHeader(HeaderConfig.X_ATMOSPHERE_ERROR, "rejected");
        assertEquals(1, interceptor.totalRejected());
    }

    @Test
    public void testExpiredTokenWithoutRefresherDisconnects() {
        interceptor = new AuthInterceptor(alwaysExpired);

        var resource = mockResource("client7", "expired-token", null);
        var action = interceptor.inspect(resource);

        assertEquals(Action.CANCELLED, action);
        verify(resource.getResponse()).setHeader(HeaderConfig.X_ATMOSPHERE_AUTH_EXPIRED, "token expired");
        assertEquals(1, interceptor.totalRejected());
    }

    @Test
    public void testExpiredTokenWithSuccessfulRefresh() {
        TokenValidator validator = token -> {
            if ("refreshed-token".equals(token)) {
                return new TokenValidator.Valid("refreshed-user");
            }
            return new TokenValidator.Expired("expired");
        };
        TokenRefresher refresher = expired -> Optional.of("refreshed-token");

        interceptor = new AuthInterceptor(validator, refresher);

        var resource = mockResource("client8", "old-token", null);
        var action = interceptor.inspect(resource);

        assertEquals(Action.CONTINUE, action);
        verify(resource.getResponse()).setHeader(HeaderConfig.X_ATMOSPHERE_AUTH_REFRESH, "refreshed-token");
        assertEquals(1, interceptor.totalRefreshed());
        assertEquals(0, interceptor.totalRejected());
    }

    @Test
    public void testExpiredTokenWithFailedRefresh() {
        TokenRefresher refresher = expired -> Optional.empty();

        interceptor = new AuthInterceptor(alwaysExpired, refresher);

        var resource = mockResource("client9", "old-token", null);
        var action = interceptor.inspect(resource);

        assertEquals(Action.CANCELLED, action);
        assertEquals(1, interceptor.totalRejected());
        assertEquals(0, interceptor.totalRefreshed());
    }

    @Test
    public void testExpiredTokenRefreshedButRevalidationFails() {
        // The refresher returns a new token, but the validator rejects even the new token
        TokenRefresher refresher = expired -> Optional.of("still-bad");

        interceptor = new AuthInterceptor(alwaysInvalid, refresher);

        var resource = mockResource("client10", "old-token", null);
        // First call returns Invalid (since alwaysInvalid), not Expired
        // We need a validator that returns Expired for old tokens, Invalid for new
        TokenValidator mixedValidator = token -> {
            if ("old-token".equals(token)) {
                return new TokenValidator.Expired();
            }
            return new TokenValidator.Invalid("still invalid after refresh");
        };
        interceptor = new AuthInterceptor(mixedValidator, refresher);

        var action = interceptor.inspect(resource);

        assertEquals(Action.CANCELLED, action);
        assertEquals(1, interceptor.totalRejected());
    }

    @Test
    public void testWebSocketMessageSkipsReauth() {
        interceptor = new AuthInterceptor(alwaysValid);

        // First: authenticate on handshake
        var resource = mockResource("ws-client", "valid-token", null);
        var action = interceptor.inspect(resource);
        assertEquals(Action.CONTINUE, action);

        // Second: simulate post-upgrade WebSocket message
        when(resource.getRequest(false).getAttribute(FrameworkConfig.WEBSOCKET_MESSAGE))
                .thenReturn(Boolean.TRUE);

        action = interceptor.inspect(resource);
        assertEquals(Action.CONTINUE, action);
    }

    @Test
    public void testNoValidatorContinues() {
        interceptor = new AuthInterceptor();
        // configure() not called or no validator set

        var resource = mockResource("client-no-validator", "some-token", null);
        var action = interceptor.inspect(resource);

        assertEquals(Action.CONTINUE, action);
    }

    @Test
    public void testPriorityIsFirstBeforeDefault() {
        interceptor = new AuthInterceptor(alwaysValid);
        assertEquals(InvokationOrder.FIRST_BEFORE_DEFAULT, interceptor.priority());
    }

    @Test
    public void testDestroyClears() {
        interceptor = new AuthInterceptor(alwaysValid);

        var resource = mockResource("client-destroy", "token", null);
        interceptor.inspect(resource);
        assertEquals(1, interceptor.authenticatedCount());

        interceptor.destroy();
        assertEquals(0, interceptor.authenticatedCount());
    }

    @Test
    public void testCounters() {
        interceptor = new AuthInterceptor(alwaysValid);
        assertEquals(0, interceptor.totalRejected());
        assertEquals(0, interceptor.totalRefreshed());
        assertEquals(0, interceptor.authenticatedCount());
    }

    @Test
    public void testToString() {
        interceptor = new AuthInterceptor(alwaysValid);
        var str = interceptor.toString();
        assertTrue(str.contains("AuthInterceptor"));
        assertTrue(str.contains("disconnectOnFailure=true"));
    }

    @Test
    public void testToStringNoValidator() {
        interceptor = new AuthInterceptor();
        var str = interceptor.toString();
        assertTrue(str.contains("NONE"));
    }
}
