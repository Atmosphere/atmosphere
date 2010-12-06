package org.atmosphere.di;

/**
 * Represent an injector, capable of providing instances or inject resources into objects.
 * Implementations are likely to delegate to some DI frameworks like Google Guice or Spring
 *
 * @author Mathieu Carbou
 * @since 0.7
 */
public interface Injector {

    /**
     * Asks the underlying DI framework to inject resources into this instance. This method can be used when instances
     * are not created by the DI framework but with Atmosphere instead.
     *
     * @param o The instance to inject
     */
    void inject(Object o);
}
