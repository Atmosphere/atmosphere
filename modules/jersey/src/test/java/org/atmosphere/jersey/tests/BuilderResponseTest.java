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

import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.Response;
import org.atmosphere.container.BlockingIOCometSupport;
import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AtmosphereServlet;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.concurrent.TimeUnit;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

public class BuilderResponseTest extends BlockingIOJerseyTest {

    @Override
    public void configureCometSupport() {
        atmoServlet.framework().setAsyncSupport(new BlockingIOCometSupport(atmoServlet.framework().getAtmosphereConfig()));
    }

    @Override
    @BeforeMethod(alwaysRun = true)
    public void setUpGlobal() throws Exception {
        port = TestHelper.getEnvVariable("ATMOSPHERE_HTTP_PORT", findFreePort());
        urlTarget = "http://127.0.0.1:" + port + "/builder/invoke";
        atmoServlet = new AtmosphereServlet();
        atmoServlet.framework().addInitParameter("com.sun.jersey.config.property.packages", this.getClass().getPackage().getName());
        atmoServlet.framework().addInitParameter(ApplicationConfig.SUPPORT_LOCATION_HEADER, "true");
        configureCometSupport();
        startServer();
    }

    @Test(timeOut = 20000, enabled = true)
    public void test200WithNoContent() {
        logger.info("{}: running test: test200WithNoContent", getClass().getSimpleName());

        AsyncHttpClient c = new AsyncHttpClient();
        urlTarget = "http://127.0.0.1:" + port + "/builder/invoke/204";
        try {
            long t1 = System.currentTimeMillis();
            Response r = c.prepareGet(urlTarget).execute().get(10, TimeUnit.SECONDS);
            assertNotNull(r);
            assertEquals(r.getStatusCode(), 200);
            String resume = r.getResponseBody();
            long current = System.currentTimeMillis() - t1;
            assertTrue(current > 5000 && current < 10000);
        } catch (Exception e) {
            logger.error("test failed", e);
            fail(e.getMessage());
        }

        c.close();
    }

}
