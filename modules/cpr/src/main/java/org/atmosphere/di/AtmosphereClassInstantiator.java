package org.atmosphere.di;

import org.atmosphere.cpr.AtmosphereFramework;

public interface AtmosphereClassInstantiator {
	public <T> T newClassInstance(AtmosphereFramework framework, Class<T> classToInstantiate) throws InstantiationException, IllegalAccessException;
}
