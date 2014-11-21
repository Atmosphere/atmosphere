package org.atmosphere.cpr;

import org.atmosphere.inject.Configurable;

/**
 * Customization point for Atmosphere to instantiate classes.
 * Useful when using a DI framework.
 *
 * @author Norman Franke
 * @author Jeanfrancois Arcand
 */
public interface AtmosphereObjectFactory extends Configurable {

    /**
     * Delegate the creation of Object to the underlying object provider like Spring, Guice, etc.
     *
     * When creating a class, it is important to check if the class can be configured via its implementation
     * of the {@link org.atmosphere.inject.Configurable}. {@link org.atmosphere.inject.Configurable#configure(AtmosphereConfig)}
     * should be called in that case.
     *
     * @param classType The class' type to be created
     * @param defaultType a class to be created  @return  an instance of T
     * @throws InstantiationException
     * @throws IllegalAccessException
     */
	public <T, U extends T> T newClassInstance(Class<T> classType, Class<U> defaultType) throws InstantiationException, IllegalAccessException;
}
