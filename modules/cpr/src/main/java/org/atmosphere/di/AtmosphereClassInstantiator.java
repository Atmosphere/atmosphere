package org.atmosphere.di;

import org.atmosphere.cpr.AtmosphereFramework;

/**
 * Customization point for Atmosphere to instantiate classes.
 * Useful when using a DI framework.
 *
 * @author Norman Franke
 *
 */
public interface AtmosphereClassInstantiator {
	public <T> T newClassInstance(AtmosphereFramework framework, Class<T> classToInstantiate) throws InstantiationException, IllegalAccessException;
}
