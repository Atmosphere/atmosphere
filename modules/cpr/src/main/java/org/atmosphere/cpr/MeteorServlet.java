/*
 * Copyright 2014 Jeanfrancois Arcand
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
/*
 *
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 2007-2008 Sun Microsystems, Inc. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License. You can obtain
 * a copy of the License at https://glassfish.dev.java.net/public/CDDL+GPL.html
 * or glassfish/bootstrap/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/bootstrap/legal/LICENSE.txt.
 * Sun designates this particular file as subject to the "Classpath" exception
 * as provided by Sun in the GPL Version 2 section of the License file that
 * accompanied this code.  If applicable, add the following below the License
 * Header, with the fields enclosed by brackets [] replaced by your own
 * identifying information: "Portions Copyrighted [year]
 * [name of copyright owner]"
 *
 * Contributor(s):
 *
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 *
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

/**
 * Simple Servlet to use when Atmosphere {@link Meteor} are used. This Servlet will look
 * for a Servlet init-param named org.atmosphere.servlet or org.atmosphere.filter and will
 * delegate request processing to them. When used, this Servlet will ignore any
 * value defined in META-INF/atmosphere.xml as internally it will create a {@link ReflectorServletProcessor}.
 *
 * @author Jean-Francois Arcand
 */
public class MeteorServlet extends AtmosphereServlet {

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
        super.init(sc);

        if (delegate == null) {
            loadDelegateViaConfig(sc);
        } else {
            ReflectorServletProcessor r = new ReflectorServletProcessor(delegate);
            for (Filter f : filters) {
                r.addFilter(f);
            }
            BroadcasterFactory.getDefault().remove(delegateMapping);
            framework.addAtmosphereHandler(delegateMapping, r).initAtmosphereHandler(sc);
        }
    }

    private void loadDelegateViaConfig(ServletConfig sc) throws ServletException {
        String servletClass = framework().getAtmosphereConfig().getInitParameter(SERVLET_CLASS);
        String mapping = framework().getAtmosphereConfig().getInitParameter(MAPPING);
        String filterClass = framework().getAtmosphereConfig().getInitParameter(FILTER_CLASS);
        String filterName = framework().getAtmosphereConfig().getInitParameter(FILTER_NAME);

        if (servletClass != null) {
            logger.info("Installed Servlet/Meteor {} mapped to {}", servletClass, mapping == null ? "/*" : mapping);
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
                mapping = "/*";
                framework.getBroadcasterFactory().remove("/*");
            }
            framework.addAtmosphereHandler(mapping, r).initAtmosphereHandler(sc);
        }
    }

    @Override
    public void destroy() {
        super.destroy();
    }
}
