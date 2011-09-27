package org.atmosphere.guice;

import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Scope;
import com.google.inject.Singleton;
import com.google.inject.servlet.ServletScopes;
import com.sun.jersey.api.core.DefaultResourceConfig;
import com.sun.jersey.api.core.ResourceConfig;
import com.sun.jersey.core.spi.component.ComponentScope;
import com.sun.jersey.core.spi.component.ioc.IoCComponentProviderFactory;
import com.sun.jersey.guice.spi.container.GuiceComponentProviderFactory;
import com.sun.jersey.spi.container.WebApplication;
import com.sun.jersey.spi.container.servlet.ServletContainer;
import com.sun.jersey.spi.container.servlet.WebConfig;

import javax.servlet.ServletException;
import java.util.Map;

/**
 * A {@link Servlet} or {@link Filter} for deploying root resource classes
 * with Guice integration.
 * <p>
 * This class must be registered using
 * <code>com.google.inject.servlet.ServletModule</code>.
 * <p>
 * This class extends {@link ServletContainer} and initiates the
 * {@link WebApplication} with a Guice-based {@link IoCComponentProviderFactory},
 * {@link GuiceComponentProviderFactory}, such that instances of resource and
 * provider classes declared and managed by Guice can be obtained.
 * <p>
 * Guice-bound classes will be automatically registered if such
 * classes are root resource classes or provider classes. It is not necessary
 * to provide initialization parameters for declaring classes in the web.xml
 * unless a mixture of Guice-bound and Jersey-managed classes is required.
 *
 * @author Gili Tzabari
 * @author Paul Sandoz
 * @see com.google.inject.servlet.ServletModule
 */
@Singleton
public class GuiceContainer extends ServletContainer {

    private static final long serialVersionUID = 1931878850157940335L;

    private final Injector injector;
    private WebApplication webapp;

    public class ServletGuiceComponentProviderFactory extends GuiceComponentProviderFactory {
        public ServletGuiceComponentProviderFactory(ResourceConfig config, Injector injector) {
            super(config, injector);
        }

        @Override
        public Map<Scope, ComponentScope> createScopeMap() {
            Map<Scope, ComponentScope> m = super.createScopeMap();

            m.put(ServletScopes.REQUEST, ComponentScope.PerRequest);
            return m;
        }
    }
    /**
     * Creates a new Injector.
     *
     * @param injector the Guice injector
     */
    @Inject
    public GuiceContainer(Injector injector) {
        this.injector = injector;
    }

    @Override
    protected ResourceConfig getDefaultResourceConfig(Map<String, Object> props,
            WebConfig webConfig) throws ServletException {
        return new DefaultResourceConfig();
    }

    @Override
    protected void initiate(ResourceConfig config, WebApplication webapp) {
        this.webapp = webapp;
        webapp.initiate(config, new ServletGuiceComponentProviderFactory(config, injector));
    }

    public WebApplication getWebApplication() {
        return webapp;
    }
}