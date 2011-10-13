/*
 *
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 1997-2007 Sun Microsystems, Inc. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License. You can obtain
 * a copy of the License at https://jersey.dev.java.net/CDDL+GPL.html
 * or jersey/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at jersey/legal/LICENSE.txt.
 * Sun designates this particular file as subject to the "Classpath" exception
 * as provided by Sun in the GPL Version 2 section of the License file that
 * accompanied this code.  If applicable, add the following below the License
 * Header, with the fields enclosed by brackets [] replaced by your own
 * identifying information: "Portions Copyrighted [year]
 * [name of copyright owner]"
 *
 * Contributor(s):
 *
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
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
import org.atmosphere.cpr.AtmosphereServlet;
import org.atmosphere.cpr.CometSupport;
import org.testng.annotations.BeforeMethod;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import java.io.File;

import static org.atmosphere.cpr.ApplicationConfig.MAX_INACTIVE;

public class TomcatJerseyTest extends BasePubSubTest {

    protected Embedded embedded;

    public static class TomcatAtmosphereServlet extends AtmosphereServlet {

        public void init(final ServletConfig sc) throws ServletException {
            addInitParameter(MAX_INACTIVE, "20000");
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