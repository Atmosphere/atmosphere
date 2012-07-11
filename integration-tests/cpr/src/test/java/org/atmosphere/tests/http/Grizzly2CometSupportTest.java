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

package org.atmosphere.tests.http;


import org.atmosphere.container.Grizzly2CometSupport;
import org.atmosphere.cpr.AtmosphereServlet;
import org.glassfish.grizzly.comet.CometAddOn;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.grizzly.http.server.NetworkListener;
import org.glassfish.grizzly.servlet.ServletRegistration;
import org.glassfish.grizzly.servlet.WebappContext;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;

public class Grizzly2CometSupportTest extends BaseTest {

    protected HttpServer ws;

    @BeforeMethod(alwaysRun = true)
    public void startServer() throws Exception {

        int port = TestHelper.getEnvVariable("ATMOSPHERE_HTTP_PORT", findFreePort());
        urlTarget = "http://127.0.0.1:" + port + "/invoke";

        ws = new HttpServer();
        NetworkListener listener = new NetworkListener("listener", "127.0.0.1", port);
        ws.addListener(listener);

        listener.registerAddOn(new CometAddOn());
        WebappContext webappContext = new WebappContext("Grizzly 2 Comet Test");
        atmoServlet = new AtmosphereServlet();
        ServletRegistration registration = webappContext.addServlet("AtmosphereServlet", atmoServlet);
        registration.addMapping("/*");
        registration.setLoadOnStartup(0);

        configureCometSupport();
        webappContext.deploy(ws);

        ws.start();
    }

    public void configureCometSupport() {
        atmoServlet.framework().setAsyncSupport(new Grizzly2CometSupport(atmoServlet.framework().getAtmosphereConfig()));
    }

    @AfterMethod(alwaysRun = true)
    public void unsetAtmosphereHandler() throws Exception {
        atmoServlet.framework().destroy();
        ws.stop();
    }

}