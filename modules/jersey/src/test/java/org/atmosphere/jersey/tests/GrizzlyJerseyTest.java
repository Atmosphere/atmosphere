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

import com.ning.http.client.AsyncCompletionHandler;
import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.Response;
import com.sun.grizzly.comet.CometAsyncFilter;
import com.sun.grizzly.http.embed.GrizzlyWebServer;
import com.sun.grizzly.http.servlet.ServletAdapter;
import org.atmosphere.cache.HeaderBroadcasterCache;
import org.atmosphere.container.GrizzlyCometSupport;
import org.testng.annotations.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.fail;


public class GrizzlyJerseyTest extends BasePubSubTest {

    protected GrizzlyWebServer ws;
    protected ServletAdapter sa;

    @Override
    public void configureCometSupport() {
        atmoServlet.setCometSupport(new GrizzlyCometSupport(atmoServlet.getAtmosphereConfig()));
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