package org.atmosphere.samples.di.guice;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.servlet.GuiceServletContextListener;
import com.google.inject.servlet.ServletModule;
import com.sun.jersey.guice.spi.container.servlet.GuiceContainer;
import org.atmosphere.guice.GuiceManagedAtmosphereServlet;

import java.util.HashMap;

/**
 * @author Mathieu Carbou
 * @since 0.7
 */
public final class GuiceContextListener extends GuiceServletContextListener {
    @Override
    protected Injector getInjector() {
        return Guice.createInjector(new ServletModule() {
            @Override
            protected void configureServlets() {
                bind(MessageResource.class);
                serve("/async/*").with(GuiceManagedAtmosphereServlet.class, new HashMap<String, String>() {
                    {
                        put("org.atmosphere.useWebSocket", "true");
                        put("org.atmosphere.useNative", "true");
                    }
                });
                serve("/rest/*").with(GuiceContainer.class);
            }
        });
    }
}
