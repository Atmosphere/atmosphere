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

import org.atmosphere.container.BlockingIOCometSupport;
import org.atmosphere.cpr.AtmosphereRequest.NoOpsRequest;
import org.atmosphere.util.FakeHttpSession;
import org.testng.annotations.Test;

import javax.servlet.ServletConfig;
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
        AtmosphereRequest request = new AtmosphereRequest.Builder().build();

        assertNull(request.getSession(false));
        assertNotNull(request.getSession());
        assertNotNull(request.getSession(true));
        assertNotNull(request.getSession());

        request = new AtmosphereRequest.Builder().session(new FakeHttpSession("-1", null, System.currentTimeMillis(), -1)).build();
        assertNotNull(request.getSession());
        assertNotNull(request.getSession(true));
    }

    @Test
    public void basicAtmosphereResourceSessionTest() throws IOException, ServletException, ExecutionException, InterruptedException {
        AtmosphereRequest request = new AtmosphereRequest.Builder().build();
        AtmosphereResponse response = new AtmosphereResponse.Builder().build();

        AtmosphereResource r = AtmosphereResourceFactory.getDefault().create(new AtmosphereFramework().getAtmosphereConfig(),
                request,
                response,
                mock(AsyncSupport.class));

        assertNull(r.session(false));
        assertNotNull(r.session());
        assertNotNull(r.session(true));
        assertNotNull(r.session());

        request = new AtmosphereRequest.Builder().session(new FakeHttpSession("-1", null, System.currentTimeMillis(), -1)).build();
        response = new AtmosphereResponse.Builder().build();
        r = AtmosphereResourceFactory.getDefault().create(new AtmosphereFramework().getAtmosphereConfig(),
                request,
                response,
                mock(AsyncSupport.class));

        assertNotNull(r.session());
        assertNotNull(r.session(true));
    }
    
    @Test
    public void sessionReplacementTest() {
    	AtmosphereConfig config = new AtmosphereFramework().getAtmosphereConfig();
    	config.setSupportSession(true);
    	
    	HttpServletRequest httpRequest = new NoOpsRequest();
        AtmosphereRequest request = new AtmosphereRequest.Builder().request(httpRequest).session(httpRequest.getSession(true)).build();
        AtmosphereResponse response = new AtmosphereResponse.Builder().build();
        AtmosphereResource r = AtmosphereResourceFactory.getDefault().create(config, request, response, mock(AsyncSupport.class));
        
        request.setAttribute(FrameworkConfig.ATMOSPHERE_RESOURCE, r);

        assertNotNull(request.getSession());
        request.getSession().invalidate();
        assertNull(request.getSession(false));
        assertNotNull(r.session(true));
    }
}
