package org.atmosphere.di;

/**
 * Default injector which does nothing
 *
 * @author Mathieu Carbou
 * @since 0.7
 */
final class NoopInjector implements Injector {
    public void inject(Object o) {
    }
}
