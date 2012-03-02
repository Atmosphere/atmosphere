/*
 * Copyright 2012 Jeanfrancois Arcand
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
package org.atmosphere.jersey.tests;

import com.sun.grizzly.comet.CometAsyncFilter;
import com.sun.grizzly.http.embed.GrizzlyWebServer;
import com.sun.grizzly.http.servlet.ServletAdapter;
import org.atmosphere.container.GrizzlyCometSupport;
import org.testng.annotations.Test;


public class GrizzlyJerseyTest extends BasePubSubTest {

    protected GrizzlyWebServer ws;
    protected ServletAdapter sa;

    @Override
    public void configureCometSupport() {
        atmoServlet.framework().setCometSupport(new GrizzlyCometSupport(atmoServlet.framework().getAtmosphereConfig()));
    }

    @Override
    public void startServer() throws Exception {
        ws = new GrizzlyWebServer(port);
        sa = new ServletAdapter();
        ws.addAsyncFilter(new CometAsyncFilter());
        sa.setServletInstance(atmoServlet);
        ws.addGrizzlyAdapter(sa, new String[]{ROOT});
        ws.start();
    }

    @Override
    public void stopServer() throws Exception {
        ws.stop();
    }

    /*
      Grizzly ServletContainer throws an exception java.lang.IllegalStateException: ServletConfig has not been initialized
      disable the test for now.
     */
    @Test(timeOut = 20000, enabled = false)
    public void testHeaderBroadcasterCache() throws IllegalAccessException, ClassNotFoundException, InstantiationException {
    }

}