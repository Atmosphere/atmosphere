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
     * Configure the factory using the {@link org.atmosphere.cpr.AtmosphereConfig}
     * @param config {@link org.atmosphere.cpr.AtmosphereConfig}
     */
    public void configure(AtmosphereConfig config);

    /**
     * Delegate the creation of Object to the underlying object provider like Spring, Guice, etc.
     *
     *
     * @param classType The class' type to be created
     * @param defaultType a class to be created  @return  an instance of T
     * @throws InstantiationException
     * @throws IllegalAccessException
     */
	public <T, U extends T> T newClassInstance(Class<T> classType, Class<U> defaultType) throws InstantiationException, IllegalAccessException;
}
