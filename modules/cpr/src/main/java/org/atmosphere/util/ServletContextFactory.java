/*
 * Copyright 2013 Jeanfrancois Arcand
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.atmosphere.util;

import javax.servlet.ServletContext;
import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A simple Factory to get access to the {@linl ServletContext}. This factory won't work if more than one Servlet is deployed inside the same war.
 *
 * @author Jeanfrancois Arcand
 */
public class ServletContextFactory {

    private AtomicReference<WeakReference<ServletContext>> servletContext = new AtomicReference<WeakReference<ServletContext>>();
    private static ServletContextFactory servletContextFactory;

    private ServletContextFactory(){}

    /**
     * Set the ServletContext
     * @param ctx ServletContext
     */
    public void init(ServletContext ctx) {
        servletContext.set(new WeakReference<ServletContext>(ctx));
    }

    public ServletContext getServletContext() {
        WeakReference<ServletContext> ref = servletContext.get();
        if (ref != null) {
            ServletContext ctx = ref.get();
            if (ctx != null) {
                return ctx;
            }
        }
        return null;
    }

    public final static ServletContextFactory getDefault() {
        if (servletContextFactory == null) servletContextFactory = new ServletContextFactory();
        return servletContextFactory;
    }
}
