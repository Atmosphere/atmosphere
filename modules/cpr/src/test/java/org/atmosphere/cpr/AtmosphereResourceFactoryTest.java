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

import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import java.util.Enumeration;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.testng.AssertJUnit.assertEquals;
import static org.testng.AssertJUnit.assertFalse;
import static org.testng.AssertJUnit.assertNotNull;
import static org.testng.AssertJUnit.assertNull;
import static org.testng.AssertJUnit.assertTrue;

public class AtmosphereResourceFactoryTest {

    private AtmosphereFramework framework;

    @BeforeMethod
    public void create() throws Throwable {
        framework = new AtmosphereFramework();
        framework.setAsyncSupport(mock(AsyncSupport.class));
        framework.init(new ServletConfig() {
            @Override
            public String getServletName() {
                return "void";
            }

            @Override
            public ServletContext getServletContext() {
                return mock(ServletContext.class);
            }

            @Override
            public String getInitParameter(String name) {
                return null;
            }

            @Override
            public Enumeration<String> getInitParameterNames() {
                return null;
            }
        });
    }

    @Test
    public void createTest() {
        AtmosphereResource r = framework.getAtmosphereConfig().resourcesFactory().create(framework.getAtmosphereConfig(), mock(Broadcaster.class), AtmosphereResponseImpl.newInstance().request(AtmosphereRequestImpl.newInstance()),
                mock(AsyncSupport.class), mock(AtmosphereHandler.class), AtmosphereResource.TRANSPORT.WEBSOCKET);
        assertNotNull(r);
    }

    @Test
    public void findTest() {
        Broadcaster b1 = framework.getBroadcasterFactory().get("b1");
        Broadcaster b2 = framework.getBroadcasterFactory().get("b2");
        AtmosphereResource r = framework.getAtmosphereConfig().resourcesFactory().create(framework.getAtmosphereConfig(), b1, AtmosphereResponseImpl.newInstance().request(AtmosphereRequestImpl.newInstance()),
                mock(AsyncSupport.class), mock(AtmosphereHandler.class), AtmosphereResource.TRANSPORT.WEBSOCKET);
        assertNotNull(r);

        b2.addAtmosphereResource(r.suspend());

        assertNotNull(framework.getAtmosphereConfig().resourcesFactory().find(r.uuid()));

    }

    @Test
    public void notFoundTest() {
        for (int i = 0; i < 10; i++) {
            framework.getBroadcasterFactory().get(String.valueOf(i));
        }
        AtmosphereResource r = framework.getAtmosphereConfig().resourcesFactory().create(framework.getAtmosphereConfig(), framework.getBroadcasterFactory().lookup("1"), AtmosphereResponseImpl.newInstance().request(AtmosphereRequestImpl.newInstance()),
                mock(AsyncSupport.class), mock(AtmosphereHandler.class));
        assertNotNull(r);
        assertNull(framework.getAtmosphereConfig().resourcesFactory().find(r.uuid()));
    }

    @Test
    public void deleteTest() {
        for (int i = 0; i < 10; i++) {
            framework.getBroadcasterFactory().get(String.valueOf(i));
        }
        Broadcaster b2 = framework.getBroadcasterFactory().get("b2");
        AtmosphereResource r = framework.getAtmosphereConfig().resourcesFactory().create(framework.config, framework.getBroadcasterFactory().lookup("1"), AtmosphereResponseImpl.newInstance().request(AtmosphereRequestImpl.newInstance()),
                mock(AsyncSupport.class), mock(AtmosphereHandler.class), AtmosphereResource.TRANSPORT.WEBSOCKET);
        assertNotNull(r);
        b2.addAtmosphereResource(r.suspend());

        assertNotNull(framework.getAtmosphereConfig().resourcesFactory().find(r.uuid()));
        assertEquals(framework.getAtmosphereConfig().resourcesFactory().remove(r.uuid()), r);
        assertNull(framework.getAtmosphereConfig().resourcesFactory().find(r.uuid()));
    }

    @Test
    public void broadcastersTest() {
        Broadcaster b1 = framework.getBroadcasterFactory().get("b1");
        Broadcaster b2 = framework.getBroadcasterFactory().get("b2");
        AtmosphereResource r = framework.getAtmosphereConfig().resourcesFactory().create(framework.getAtmosphereConfig(), b1, AtmosphereResponseImpl.newInstance().request(AtmosphereRequestImpl.newInstance()),
                mock(AsyncSupport.class), mock(AtmosphereHandler.class), AtmosphereResource.TRANSPORT.WEBSOCKET);
        r.suspend();
        assertNotNull(r);

        b2.addAtmosphereResource(r);

        assertNotNull(framework.getAtmosphereConfig().resourcesFactory().find(r.uuid()));
        int size = framework.getAtmosphereConfig().resourcesFactory().find(r.uuid()).broadcasters().size();
        assertEquals(2, size);

    }

    @Test
    public void findResourceReturnsOptionalTest() {
        Broadcaster b1 = framework.getBroadcasterFactory().get("b1");
        AtmosphereResource r = framework.getAtmosphereConfig().resourcesFactory().create(framework.getAtmosphereConfig(), b1, AtmosphereResponseImpl.newInstance().request(AtmosphereRequestImpl.newInstance()),
                mock(AsyncSupport.class), mock(AtmosphereHandler.class), AtmosphereResource.TRANSPORT.WEBSOCKET);
        assertNotNull(r);
        b1.addAtmosphereResource(r.suspend());

        Optional<AtmosphereResource> result = framework.getAtmosphereConfig().resourcesFactory().findResource(r.uuid());
        assertTrue(result.isPresent());
        assertEquals(r.uuid(), result.get().uuid());
    }

    @Test
    public void findResourceReturnsEmptyForUnknownUuidTest() {
        Optional<AtmosphereResource> result = framework.getAtmosphereConfig().resourcesFactory().findResource("nonexistent-uuid");
        assertFalse(result.isPresent());
    }

    @Test
    public void findResourceReturnsEmptyForNullUuidTest() {
        Optional<AtmosphereResource> result = framework.getAtmosphereConfig().resourcesFactory().findResource(null);
        assertFalse(result.isPresent());
    }

    @Test
    public void findResourceReturnsEmptyAfterRemoveTest() {
        Broadcaster b1 = framework.getBroadcasterFactory().get("b1");
        AtmosphereResource r = framework.getAtmosphereConfig().resourcesFactory().create(framework.config, b1, AtmosphereResponseImpl.newInstance().request(AtmosphereRequestImpl.newInstance()),
                mock(AsyncSupport.class), mock(AtmosphereHandler.class), AtmosphereResource.TRANSPORT.WEBSOCKET);
        assertNotNull(r);
        b1.addAtmosphereResource(r.suspend());

        assertTrue(framework.getAtmosphereConfig().resourcesFactory().findResource(r.uuid()).isPresent());
        framework.getAtmosphereConfig().resourcesFactory().remove(r.uuid());
        assertFalse(framework.getAtmosphereConfig().resourcesFactory().findResource(r.uuid()).isPresent());
    }
}
