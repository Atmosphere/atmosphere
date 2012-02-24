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

import org.apache.catalina.Context;
import org.apache.catalina.Engine;
import org.apache.catalina.Host;
import org.apache.catalina.Wrapper;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Embedded;
import org.apache.coyote.http11.Http11NioProtocol;
import org.atmosphere.container.TomcatCometSupport;
import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AtmosphereServlet;
import org.testng.annotations.BeforeMethod;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import java.io.File;


public class TomcatJerseyTest extends BasePubSubTest {

    protected Embedded embedded;

    public static class TomcatAtmosphereServlet extends AtmosphereServlet {

        public void init(final ServletConfig sc) throws ServletException {
            addInitParameter(ApplicationConfig.MAX_INACTIVE, "20000");
            addInitParameter("com.sun.jersey.config.property.packages", this.getClass().getPackage().getName());
            addInitParameter("org.atmosphere.cpr.broadcasterClass", RecyclableBroadcaster.class.getName());
            cometSupport = new TomcatCometSupport(getAtmosphereConfig());
            super.init(sc);
        }

    }

    @BeforeMethod(alwaysRun = true)
    public void setUpGlobal() throws Exception {
        System.setProperty("org.atmosphere.useNative", "true");

        port = TestHelper.getEnvVariable("ATMOSPHERE_HTTP_PORT", findFreePort());
        urlTarget = "http://127.0.0.1:" + port + "/invoke";
        configureCometSupport();
        startServer();
    }

    @Override
    public void startServer() throws Exception {
        embedded = new Embedded();
        String path = new File(".").getAbsolutePath();
        embedded.setCatalinaHome(path);

        Engine engine = embedded.createEngine();
        engine.setDefaultHost("127.0.0.1");

        Host host = embedded.createHost("127.0.0.1", path);
        engine.addChild(host);

        Context c = embedded.createContext("/", path);
        c.setReloadable(false);
        Wrapper w = c.createWrapper();
        w.addMapping("/*");
        w.setServletClass(TomcatAtmosphereServlet.class.getName());
        w.setLoadOnStartup(0);

        c.addChild(w);
        host.addChild(c);

        Connector connector = embedded.createConnector("127.0.0.1", port, Http11NioProtocol.class.getName());
        connector.setContainer(host);
        embedded.addEngine(engine);
        embedded.addConnector(connector);
        embedded.start();

        atmoServlet = (AtmosphereServlet) w.getServlet();

    }

    @Override
    public void configureCometSupport() {
    }

    @Override
    public void stopServer() throws Exception {
        if (atmoServlet != null) atmoServlet.destroy();
        embedded.stop();
    }
}