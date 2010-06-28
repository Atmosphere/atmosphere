package org.atmosphere.jersey.tests;

import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.Response;
import org.atmosphere.container.BlockingIOCometSupport;
import org.atmosphere.cpr.AtmosphereServlet;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.concurrent.TimeUnit;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

public class BuilderResponseTest extends BlockingIOJerseyTest{

    @Override
    public void configureCometSupport() {
        atmoServlet.setCometSupport(new BlockingIOCometSupport(atmoServlet.getAtmosphereConfig()));
    }

    @Override
    @BeforeMethod(alwaysRun = true)
    public void setUpGlobal() throws Exception {
        port = TestHelper.getEnvVariable("ATMOSPHERE_HTTP_PORT", findFreePort());
        urlTarget = "http://127.0.0.1:" + port + "/builder/invoke";
        atmoServlet = new AtmosphereServlet();
        atmoServlet.addInitParameter("com.sun.jersey.config.property.packages", this.getClass().getPackage().getName());

        configureCometSupport();
        startServer();
    }

    @Test(timeOut = 20000)
    public void test200WithNoContent() {
        System.out.println("Running testSuspendTimeout");
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
            e.printStackTrace();
            fail(e.getMessage());
        }
        c.close();

    }
}
