package org.atmosphere.jersey.tests;

import org.atmosphere.container.BlockingIOCometSupport;
import org.atmosphere.cpr.AtmosphereServlet;
import org.testng.annotations.BeforeMethod;

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

}
