package org.atmosphere.di;

import java.util.NoSuchElementException;
import java.util.ServiceLoader;

/**
 * Retreive the injector to use to inject resources into objects
 *
 * @author Mathieu Carbou
 * @since 0.7
 */
public final class InjectorProvider {

    private InjectorProvider() {
    }

    public static Injector getInjector() {
        return LazyProvider.INJECTOR;
    }

    private static final class LazyProvider {
        private static final Injector INJECTOR;

        static {
            Injector injector = new NoopInjector();
            try {
                injector = ServiceLoader.load(Injector.class).iterator().next();
            } catch (NoSuchElementException e) {
            }
            INJECTOR = injector;
        }
    }
}
