package org.atmosphere.cpr;

import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class DefaultAtmosphereResourceSession implements AtmosphereResourceSession {
	private final ConcurrentMap<String, Object> attributes = new ConcurrentHashMap<String, Object>();

	@Override
	public Object setAttribute(String name, Object value) {
		return attributes.put(name, value);
	}

	@Override
	public Object getAttribute(String name) {
		return attributes.get(name);
	}

	@Override
	public <T> T getAttribute(String name, Class<T> type) {
		return type.cast(getAttribute(name));
	}

	@Override
	public Collection<String> getAttributeNames() {
		return Collections.unmodifiableSet(attributes.keySet());
	}

	@Override
	public void destroy() {
		attributes.clear();
	}
}
