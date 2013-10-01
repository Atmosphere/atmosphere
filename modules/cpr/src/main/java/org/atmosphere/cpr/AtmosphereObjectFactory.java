package org.atmosphere.cpr;

/**
 * Customization point for Atmosphere to instantiate classes.
 * Useful when using a DI framework.
 *
 * @author Norman Franke
 * @author Jeanfrancois Arcand
 */
public interface AtmosphereObjectFactory {
    /**
     * Delegate the creation of Object to the underlying object provider like Spring, Guice, etc.
     *
     * @param framework {@link AtmosphereFramework}
     * @param classToInstantiate a class to be created
     * @param <T> The Class
     * @return  an instance of T
     * @throws InstantiationException
     * @throws IllegalAccessException
     */
	public <T> T newClassInstance(AtmosphereFramework framework, Class<T> classToInstantiate) throws InstantiationException, IllegalAccessException;
}
