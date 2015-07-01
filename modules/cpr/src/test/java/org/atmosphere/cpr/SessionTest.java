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
package org.atmosphere.cpr;

import org.atmosphere.cpr.AtmosphereRequestImpl.NoOpsRequest;
import org.atmosphere.util.FakeHttpSession;
import org.testng.annotations.Test;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.concurrent.ExecutionException;

import static org.mockito.Mockito.mock;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;

public class SessionTest {

    @Test
    public void basicSessionTest() throws IOException, ServletException, ExecutionException, InterruptedException {
        AtmosphereRequest request = new AtmosphereRequestImpl.Builder().build();

        assertNull(request.getSession(false));
        assertNotNull(request.getSession());
        assertNotNull(request.getSession(true));
        assertNotNull(request.getSession());

        request = new AtmosphereRequestImpl.Builder().session(new FakeHttpSession("-1", null, System.currentTimeMillis(), -1)).build();
        assertNotNull(request.getSession());
        assertNotNull(request.getSession(true));
    }

    @Test
    public void basicAtmosphereResourceSessionTest() throws IOException, ServletException, ExecutionException, InterruptedException {
        AtmosphereRequest request = new AtmosphereRequestImpl.Builder().build();
        AtmosphereResponse response = new AtmosphereResponseImpl.Builder().build();
        AtmosphereConfig config = new AtmosphereFramework().getAtmosphereConfig();

        AtmosphereResource r = config.resourcesFactory().create(new AtmosphereFramework().getAtmosphereConfig(),
                request,
                response,
                mock(AsyncSupport.class));

        r.getAtmosphereConfig().setSupportSession(true);

        assertNull(r.session(false));
        assertNotNull(r.session());
        assertNotNull(r.session(true));
        assertNotNull(r.session());

        request = new AtmosphereRequestImpl.Builder().session(new FakeHttpSession("-1", null, System.currentTimeMillis(), -1)).build();
        response = new AtmosphereResponseImpl.Builder().build();
        r = config.resourcesFactory().create(new AtmosphereFramework().getAtmosphereConfig(),
                request,
                response,
                mock(AsyncSupport.class));

        r.getAtmosphereConfig().setSupportSession(true);

        assertNotNull(r.session());
        assertNotNull(r.session(true));
    }

    @Test
    public void sessionReplacementTest() {
        AtmosphereConfig config = new AtmosphereFramework().getAtmosphereConfig();
        config.setSupportSession(true);

        HttpServletRequest httpRequest = new NoOpsRequest();
        AtmosphereRequest request = new AtmosphereRequestImpl.Builder().request(httpRequest).session(httpRequest.getSession(true)).build();
        AtmosphereResponse response = new AtmosphereResponseImpl.Builder().build();
        AtmosphereResource r = config.resourcesFactory().create(config, request, response, mock(AsyncSupport.class));

        request.setAttribute(FrameworkConfig.ATMOSPHERE_RESOURCE, r);

        assertNotNull(request.getSession());
        request.getSession().invalidate();
        assertNull(request.getSession(false));
        assertNotNull(r.session(true));
    }
}
