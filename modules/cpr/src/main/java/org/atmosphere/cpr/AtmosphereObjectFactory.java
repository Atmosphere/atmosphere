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
     *
     * @param framework {@link org.atmosphere.cpr.AtmosphereFramework}
     * @param classType The class' type to be created
     * @param defaultType a class to be created  @return  an instance of T
     * @throws InstantiationException
     * @throws IllegalAccessException
     */
	public <T, U extends T> T newClassInstance(AtmosphereFramework framework, Class<T> classType, Class<U> defaultType) throws InstantiationException, IllegalAccessException;
}
