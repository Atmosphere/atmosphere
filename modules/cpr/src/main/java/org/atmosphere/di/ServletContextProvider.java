package org.atmosphere.di;

import javax.servlet.ServletContext;

/**
 * Mark an object as being able to provide a ServletContext
 *
 * @author Mathieu Carbou
 * @since 0.7
 */
public interface ServletContextProvider {
    ServletContext getServletContext();
}
