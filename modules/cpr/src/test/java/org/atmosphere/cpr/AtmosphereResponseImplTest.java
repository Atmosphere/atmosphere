package org.atmosphere.cpr;

import static org.mockito.Mockito.mock;
import static org.testng.Assert.assertEquals;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;

import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;

import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

public class AtmosphereResponseImplTest {

    private AtmosphereFramework framework;
    private AtmosphereConfig config;
    private AsynchronousProcessor processor;
    private final AtmosphereHandler handler = mock(AtmosphereHandler.class);

    @BeforeMethod
    public void create() throws Throwable {
        framework = new AtmosphereFramework();
        framework.setAsyncSupport(mock(AsyncSupport.class));
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
        processor = new AsynchronousProcessor(config) {
            @Override
            public Action service(AtmosphereRequest req, AtmosphereResponse res) throws IOException, ServletException {
                return action(req, res);
            }
        };
    }

    @Test
    public void headerTest() {
        framework.addAtmosphereHandler("/*", handler);
        AtmosphereResponse response = new AtmosphereResponseImpl.Builder().header("header 1", "header 1 value").build();
        assertEquals(response.getHeaders("header 1"), Collections.singleton("header 1 value"));
    }

    @Test
    public void headerTest2() {
        framework.addAtmosphereHandler("/*", handler);
        AtmosphereResponse response = new AtmosphereResponseImpl.Builder()
                .header("header 1", "header 1 value")
                .header("header 2", "header 2 value")
                .header("header 3", null)
                .build();
        assertEquals(response.getHeaders("header 1"), Collections.singleton("header 1 value"));
    }
}
