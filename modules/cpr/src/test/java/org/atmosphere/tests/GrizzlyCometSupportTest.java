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
package org.atmosphere.tests;

import com.sun.grizzly.comet.CometAsyncFilter;
import com.sun.grizzly.http.embed.GrizzlyWebServer;
import com.sun.grizzly.http.servlet.ServletAdapter;
import org.atmosphere.container.GrizzlyCometSupport;
import org.atmosphere.cpr.AtmosphereServlet;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;

public class GrizzlyCometSupportTest extends BaseTest {

    protected GrizzlyWebServer ws;

    @BeforeMethod(alwaysRun = true)
    public void startServer() throws Exception {

        int port = TestHelper.getEnvVariable("ATMOSPHERE_HTTP_PORT", findFreePort());
        urlTarget = "http://127.0.0.1:" + port + "/invoke";

        ws = new GrizzlyWebServer(port);
        ServletAdapter sa = new ServletAdapter();
        sa.setProperty("load-on-startup", 0);
        ws.addAsyncFilter(new CometAsyncFilter());

        atmoServlet = new AtmosphereServlet();
        //atmoServlet.addInitParameter(CometSupport.MAX_INACTIVE, "20000");
        sa.setServletInstance(atmoServlet);
        configureCometSupport();

        ws.addGrizzlyAdapter(sa, new String[]{ROOT});
        ws.start();
    }

    public void configureCometSupport() {
        atmoServlet.setCometSupport(new GrizzlyCometSupport(atmoServlet.getAtmosphereConfig()));
    }

    @AfterMethod(alwaysRun = true)
    public void unsetAtmosphereHandler() throws Exception {
        atmoServlet.destroy();
        ws.stop();
    }
}