/*
 * Copyright 2015 Async-IO.org
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

package org.atmosphere.cpr;

import org.atmosphere.handler.ReflectorServletProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.Filter;
import javax.servlet.Servlet;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;

import static org.atmosphere.cpr.ApplicationConfig.FILTER_CLASS;
import static org.atmosphere.cpr.ApplicationConfig.FILTER_NAME;
import static org.atmosphere.cpr.ApplicationConfig.MAPPING;
import static org.atmosphere.cpr.ApplicationConfig.SERVLET_CLASS;
import static org.atmosphere.cpr.Broadcaster.ROOT_MASTER;

/**
 * Simple Servlet to use when Atmosphere {@link Meteor} are used. This Servlet will look
 * for a Servlet init-param named org.atmosphere.servlet or org.atmosphere.filter and will
 * delegate request processing to them. When used, this Servlet will ignore any
 * value defined in META-INF/atmosphere.xml as internally it will create a {@link ReflectorServletProcessor}.
 *
 * @author Jean-Francois Arcand
 */
public class MeteorServlet extends AtmosphereServlet {

    private static final long serialVersionUID = 7526472295622776110L;
    protected static final Logger logger = LoggerFactory.getLogger(MeteorServlet.class);

    private Servlet delegate;

    private String delegateMapping;

    private Collection<Filter> filters;

    public MeteorServlet() {
        this(false);
    }

    public MeteorServlet(boolean isFilter) {
        this(isFilter, false);
    }

    public MeteorServlet(boolean isFilter, boolean autoDetectHandlers) {
        super(isFilter, autoDetectHandlers);
    }

    public MeteorServlet(Servlet delegate, String delegateMapping, Filter... filters) {
        this(delegate, delegateMapping, Arrays.asList(filters));
    }

    public MeteorServlet(Servlet delegate, String delegateMapping, Collection<Filter> filters) {
        this(false);
        if (delegate == null || delegateMapping == null) {
            throw new IllegalArgumentException("Delegate Servlet is undefined");
        }
        this.delegate = delegate;
        this.delegateMapping = delegateMapping;
        if (filters == null) {
            this.filters = Collections.emptyList();
        } else {
            this.filters = filters;
        }
    }

    @Override
    public void init(final ServletConfig sc) throws ServletException {
        if (!framework().isInit) {
            super.init(sc);

            if (delegate == null) {
                loadDelegateViaConfig(sc);
            } else {
                ReflectorServletProcessor r = new ReflectorServletProcessor(delegate);
                for (Filter f : filters) {
                    r.addFilter(f);
                }
                framework().getBroadcasterFactory().remove(delegateMapping);
                framework().addAtmosphereHandler(delegateMapping, r);
                framework().checkWebSocketSupportState();
            }
        }
    }

    private void loadDelegateViaConfig(ServletConfig sc) throws ServletException {
        String servletClass = framework().getAtmosphereConfig().getInitParameter(SERVLET_CLASS);
        String mapping = framework().getAtmosphereConfig().getInitParameter(MAPPING);
        String filterClass = framework().getAtmosphereConfig().getInitParameter(FILTER_CLASS);
        String filterName = framework().getAtmosphereConfig().getInitParameter(FILTER_NAME);

        if (servletClass != null) {
            logger.info("Installed Servlet/Meteor {} mapped to {}", servletClass, mapping == null ? ROOT_MASTER : mapping);
        }
        if (filterClass != null) {
            logger.info("Installed Filter/Meteor {} mapped to /*", filterClass, mapping);
        }

        // The annotation was used.
        if (servletClass != null || filterClass != null) {
            ReflectorServletProcessor r = new ReflectorServletProcessor();
            r.setServletClassName(servletClass);
            r.addFilterClassName(filterClass, filterName);
            if (mapping == null) {
                mapping = Broadcaster.ROOT_MASTER;
                framework().getBroadcasterFactory().remove(Broadcaster.ROOT_MASTER);
            }
            framework().addAtmosphereHandler(mapping, r).initAtmosphereHandler(sc);
        }
    }

    @Override
    public void destroy() {
        super.destroy();
    }
}
