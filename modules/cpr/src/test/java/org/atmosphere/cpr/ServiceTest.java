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

import org.atmosphere.config.service.AtmosphereFrameworkListenerService;
import org.atmosphere.config.service.BroadcasterService;
import org.atmosphere.util.SimpleBroadcaster;

import jakarta.servlet.ServletException;
import java.io.IOException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class ServiceTest {
    private AtmosphereFramework framework;

    @BeforeEach
    public void create() throws Throwable {
        framework = new AtmosphereFramework();
        framework.addAnnotationPackage(B.class);
        framework.setAsyncSupport(new AsynchronousProcessor(framework.getAtmosphereConfig()) {

            @Override
            public Action service(AtmosphereRequest req, AtmosphereResponse res) throws IOException, ServletException {
                return suspended(req, res);
            }

            public void action(AtmosphereResourceImpl r) {
                try {
                    resumed(r.getRequest(), r.getResponse());
                } catch (IOException e) {
                    e.printStackTrace();
                } catch (ServletException e) {
                    e.printStackTrace();
                }
            }
        }).init();
    }

    @AfterEach
    public void after() {
        framework.destroy();
    }

    @Test
    public void testBroadcasterService() throws IOException, ServletException {
        assertEquals(B.class.getName(), framework.getBroadcasterFactory().get("test").getClass().getName());
    }

    @Test
    public void testFrameworkListenerService() throws IOException, ServletException {
        assertEquals(C.class.getName(), framework.frameworkListeners().get(0).getClass().getName());
    }

    @BroadcasterService
    public final static class B extends SimpleBroadcaster {

        public B() {
        }
    }

    @AtmosphereFrameworkListenerService
    public final static class C extends AtmosphereFrameworkListenerAdapter {

        public C() {
        }
    }
}
