/*
 * Copyright 2015 Jean-Francois Arcand
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
package org.atmosphere.annotation.scanning;

import org.atmosphere.config.service.AtmosphereResourceListenerService;
import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.AsynchronousProcessor;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereRequestImpl;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereResourceListener;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.AtmosphereResponseImpl;
import org.atmosphere.handler.AtmosphereHandlerAdapter;
import org.atmosphere.util.SimpleBroadcaster;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import javax.servlet.ServletException;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.testng.Assert.assertTrue;

public class AnnotationScanningTest {
    private AtmosphereFramework framework;
    private static final AtomicBoolean suspended = new AtomicBoolean();
    private static final AtomicBoolean disconnected = new AtomicBoolean();

    @BeforeMethod
    public void create() throws Throwable {
        framework = new AtmosphereFramework();
        framework.setDefaultBroadcasterClassName(SimpleBroadcaster.class.getName());
        framework.addAnnotationPackage(AnnotationScanningTest.class);
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
        }).init().addAtmosphereHandler("/a", new AtmosphereHandlerAdapter() {
            @Override
            public void onRequest(AtmosphereResource resource) throws IOException {
                resource.suspend();
            }
        });
    }

    @AtmosphereResourceListenerService
    public final static class R implements AtmosphereResourceListener {

        @Override
        public void onSuspended(String uuid) {
            suspended.set(true);
        }

        @Override
        public void onDisconnect(String uuid) {
            disconnected.set(true);
        }
    }

    @Test
    public void testAnnotation() throws IOException, ServletException {
        AtmosphereRequest request = new AtmosphereRequestImpl.Builder().pathInfo("/a").method("GET").build();
        framework.doCometSupport(request, AtmosphereResponseImpl.newInstance());
        AsynchronousProcessor.class.cast(framework.getAsyncSupport()).endRequest((AtmosphereResourceImpl)request.resource(), true);

        assertTrue(suspended.get());
        assertTrue(disconnected.get());

    }
}
