package org.atmosphere.plugin.guice;

import org.atmosphere.di.Injector;
import org.atmosphere.di.ServletContextHolder;

/**
 * @author Mathieu Carbou
 * @since 0.7
 */
public final class GuiceInjector implements Injector {
    @Override
    public void inject(Object o) {
        com.google.inject.Injector injector = (com.google.inject.Injector)
                ServletContextHolder.getServletContext().getAttribute(com.google.inject.Injector.class.getName());
        if (injector == null)
            throw new IllegalStateException("No Guice Injector found in current ServletContext !");
        injector.injectMembers(o);
    }
}
