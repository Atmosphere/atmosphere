/*
 * Copyright 2013 Jean-Francois Arcand
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

import org.atmosphere.interceptor.AtmosphereResourceStateRecovery;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import javax.servlet.ServletException;
import java.io.IOException;

import static org.mockito.Mockito.mock;
import static org.testng.Assert.assertEquals;

public class AtmosphereResourceStateRecoveryTest {

    private AtmosphereFramework framework;
    private AtmosphereConfig config;
    private final AtmosphereResourceStateRecovery recovery = new AtmosphereResourceStateRecovery();
    private AtmosphereResource r;


    @BeforeMethod
    public void create() throws Throwable {
        framework = new AtmosphereFramework();
        framework.setAsyncSupport(mock(AsyncSupport.class));
        framework.init();
        config = framework.getAtmosphereConfig();
        r = AtmosphereResourceFactory.getDefault().create(config, "1234567");
        r.setBroadcaster(config.getBroadcasterFactory().lookup("/*", true));
    }

    @AfterMethod
    public void destroy(){
        recovery.states().clear();
        framework.destroy();
    }

    @Test
    public void basicTrackingTest() throws ServletException, IOException {
        recovery.configure(config);
        recovery.inspect(r);
        r.suspend();
        assertEquals(recovery.states().size(), 1);
    }

    @Test
    public void removeAtmosphereResourceTest() throws ServletException, IOException {
        recovery.configure(config);
        recovery.inspect(r);
        r.suspend();
        r.getBroadcaster().removeAtmosphereResource(r);
        assertEquals(recovery.states().get(r.uuid()).ids().size(), 0);
    }

    @Test
    public void cancelAtmosphereResourceTest() throws ServletException, IOException {
        recovery.configure(config);
        recovery.inspect(r);
        r.suspend();
        r.close();
        r.getBroadcaster().removeAtmosphereResource(r);
        assertEquals(recovery.states().size(), 1);
    }

    @Test
    public void restoreStateTest() throws ServletException, IOException {
        recovery.configure(config);
        recovery.inspect(r);

        config.getBroadcasterFactory().lookup("/1", true).addAtmosphereResource(r);
        config.getBroadcasterFactory().lookup("/2", true).addAtmosphereResource(r);
        config.getBroadcasterFactory().lookup("/3", true).addAtmosphereResource(r);
        config.getBroadcasterFactory().lookup("/4", true).addAtmosphereResource(r);

        r.suspend();
        r.close();

        r.getBroadcaster().removeAtmosphereResource(r);

        r.suspend();

        assertEquals(recovery.states().size(), 1);
        assertEquals(recovery.states().get(r.uuid()).ids().size(), 5);

    }

    @Test
    public void restorePartialStateTest() throws ServletException, IOException {
        recovery.configure(config);
        recovery.inspect(r);

        config.getBroadcasterFactory().lookup("/1", true).addAtmosphereResource(r);
        config.getBroadcasterFactory().lookup("/2", true).addAtmosphereResource(r);
        config.getBroadcasterFactory().lookup("/3", true).addAtmosphereResource(r);
        config.getBroadcasterFactory().lookup("/4", true).addAtmosphereResource(r);

        r.suspend();
        r.getBroadcaster().removeAtmosphereResource(r);

        r.suspend();

        assertEquals(recovery.states().size(), 1);
        assertEquals(recovery.states().get(r.uuid()).ids().size(), 4);

    }
}
