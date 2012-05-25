package org.atmosphere.cpr;


import org.atmosphere.container.BlockingIOCometSupport;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.Cookie;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.Mockito.mock;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;

public class CookieTest {

    private AtmosphereFramework framework;
    private AtmosphereConfig config;
    private AsynchronousProcessor processor;
    private final AtmosphereHandler handler = mock(AtmosphereHandler.class);

    @BeforeMethod
    public void create() throws Throwable {
        framework = new AtmosphereFramework();
        framework.setAsyncSupport(new BlockingIOCometSupport(framework.getAtmosphereConfig()));
        framework.init(new ServletConfig() {
            @Override
            public String getServletName() {
                return "void";
            }

            @Override
            public ServletContext getServletContext() {
                return mock(ServletContext.class);
            }

            @Override
            public String getInitParameter(String name) {
                return null;
            }

            @Override
            public Enumeration<String> getInitParameterNames() {
                return null;
            }
        });
        config = framework.getAtmosphereConfig();
    }


    @Test
    public void basicHandlerTest() throws IOException, ServletException, ExecutionException, InterruptedException {
        final AtomicReference<Cookie> cValue = new AtomicReference<Cookie>();
        final AtomicReference<AtmosphereResource>  r = new AtomicReference<AtmosphereResource>();

        framework.addAtmosphereHandler("/*", new AtmosphereHandler() {

            @Override
            public void onRequest(AtmosphereResource resource) throws IOException {
                r.set(resource);
                resource.getBroadcaster().addAtmosphereResource(resource);
            }

            @Override
            public void onStateChange(AtmosphereResourceEvent event) throws IOException {
                Cookie[] c = event.getResource().getRequest().getCookies();
                cValue.set(c[0]);
            }

            @Override
            public void destroy() {
            }
        });
        List<Cookie> c = new ArrayList<Cookie>();
        c.add(new Cookie("yo", "man"));

        AtmosphereRequest request = new AtmosphereRequest.Builder().cookies(c).pathInfo("/a").build();
        framework.doCometSupport(request, AtmosphereResponse.create());

        r.get().getBroadcaster().broadcast("yo").get();
        assertNotNull(cValue.get());
        assertEquals(c.get(0).getName(), cValue.get().getName());
        assertEquals(c.get(0).getValue(), cValue.get().getValue());
    }
}
