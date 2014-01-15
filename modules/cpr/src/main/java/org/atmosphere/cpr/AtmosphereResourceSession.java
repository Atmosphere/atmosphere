package org.atmosphere.cpr;

import java.util.Collection;

public interface AtmosphereResourceSession {
	Object setAttribute(String name, Object value);
	Object getAttribute(String name);
	<T> T getAttribute(String name, Class<T> type);
	Collection<String> getAttributeNames();
	void destroy();
}
