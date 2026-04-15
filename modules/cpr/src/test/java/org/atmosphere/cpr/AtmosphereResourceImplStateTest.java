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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link AtmosphereResourceImpl} state management, transport,
 * UUID, event access, and lifecycle transitions.
 */
class AtmosphereResourceImplStateTest {

    private AtmosphereFramework framework;
    private AtmosphereConfig config;
    private AtmosphereResourceImpl resource;
    private Broadcaster broadcaster;

    @BeforeEach
    void setUp() throws Exception {
        framework = new AtmosphereFramework();
        framework.setAsyncSupport(new AsynchronousProcessor(framework.getAtmosphereConfig()) {
            @Override
            public Action service(AtmosphereRequest req, AtmosphereResponse res) {
                return Action.CONTINUE;
            }
        });
        framework.init();
        config = framework.getAtmosphereConfig();

        broadcaster = config.getBroadcasterFactory().lookup(DefaultBroadcaster.class, "/test", true);

        AtmosphereRequest req = AtmosphereRequestImpl.newInstance();
        AtmosphereResponse resp = AtmosphereResponseImpl.newInstance(req);

        resource = new AtmosphereResourceImpl();
        resource.initialize(config, broadcaster, req, resp, framework.getAsyncSupport(),
                new AtmosphereHandler() {
                    @Override public void onRequest(AtmosphereResource r) { }
                    @Override public void onStateChange(AtmosphereResourceEvent event) { }
                    @Override public void destroy() { }
                });
    }

    @AfterEach
    void tearDown() {
        framework.destroy();
    }

    @Test
    void uuidShouldBeNonNullAfterInitialization() {
        assertNotNull(resource.uuid());
        assertFalse(resource.uuid().isEmpty());
    }

    @Test
    void transportShouldBeUndefinedByDefault() {
        assertEquals(AtmosphereResource.TRANSPORT.UNDEFINED, resource.transport());
    }

    @Test
    void transportSetterShouldUpdateTransport() {
        resource.transport(AtmosphereResource.TRANSPORT.WEBSOCKET);
        assertEquals(AtmosphereResource.TRANSPORT.WEBSOCKET, resource.transport());
    }

    @Test
    void getBroadcasterShouldReturnAssignedBroadcaster() {
        assertSame(broadcaster, resource.getBroadcaster());
    }

    @Test
    void getAtmosphereResourceEventShouldReturnNonNull() {
        AtmosphereResourceEventImpl event = resource.getAtmosphereResourceEvent();
        assertNotNull(event);
        assertSame(resource, event.getResource());
    }

    @Test
    void initialLifecycleStateShouldBeCreated() {
        assertEquals(AtmosphereResourceImpl.LifecycleState.CREATED, resource.lifecycleState());
    }

    @Test
    void isInScopeShouldReturnTrueForCreatedState() {
        assertTrue(resource.isInScope());
    }

    @Test
    void setIsInScopeFalseShouldTransitionToDisconnected() {
        resource.setIsInScope(false);
        assertEquals(AtmosphereResourceImpl.LifecycleState.DISCONNECTED, resource.lifecycleState());
        assertFalse(resource.isInScope());
    }

    @Test
    void resetShouldRestoreToCreatedState() {
        resource.setIsInScope(false);
        resource.reset();
        assertEquals(AtmosphereResourceImpl.LifecycleState.CREATED, resource.lifecycleState());
        assertTrue(resource.isInScope());
    }

    @Test
    void isSuspendedShouldReturnFalseInitially() {
        assertFalse(resource.isSuspended());
    }

    @Test
    void isResumedShouldReturnFalseInitially() {
        assertFalse(resource.isResumed());
    }

    @Test
    void isCancelledShouldReturnFalseInitially() {
        assertFalse(resource.isCancelled());
    }

    @Test
    void toStringShouldContainUuid() {
        String result = resource.toString();
        assertNotNull(result);
        assertTrue(result.contains(resource.uuid()));
    }

    @Test
    void actionShouldBeCreatedInitially() {
        assertEquals(Action.TYPE.CREATED, resource.action().type());
    }

    @Test
    void setActionShouldUpdateAction() {
        Action customAction = new Action(Action.TYPE.SUSPEND, 30000);
        resource.setAction(customAction);
        assertSame(customAction, resource.action());
        assertEquals(Action.TYPE.SUSPEND, resource.action().type());
    }

    @Test
    void getAtmosphereConfigShouldReturnConfig() {
        assertSame(config, resource.getAtmosphereConfig());
    }

    @Test
    void broadcastersShouldContainInitialBroadcaster() {
        assertFalse(resource.broadcasters().isEmpty());
        assertTrue(resource.broadcasters().contains(broadcaster));
    }
}
