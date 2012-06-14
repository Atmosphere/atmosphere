/*
 * Copyright 2012 Jeanfrancois Arcand
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
package org.atmosphere.cometd;

import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AtmosphereServlet;
import org.atmosphere.handler.ReflectorServletProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;

/**
 * Simple Servlet that support the Cometd.org Bayeux protocol.
 *
 * @author Jean-Francois Arcand
 */
public class CometdServlet extends AtmosphereServlet {
    protected static final Logger logger = LoggerFactory.getLogger(CometdServlet.class);

    public CometdServlet() {
        this(false);
    }

    public CometdServlet(boolean isFilter) {
        super(isFilter, false);
    }

    @Override
    public void init(final ServletConfig sc) throws ServletException {
        framework().interceptor(new CometdAtmosphereInterceptor());
        framework().setUseStreamForFlushingComments(false);

        framework().addInitParameter("transports", WebSocketTransport.class.getName());
        framework().addInitParameter(ApplicationConfig.WEBSOCKET_CONTENT_TYPE, "application/json");
        super.init(sc);

        ReflectorServletProcessor r = new ReflectorServletProcessor();
        r.setServletClassName(org.cometd.java.annotation.AnnotationCometdServlet.class.getName());
        framework.addAtmosphereHandler("/*", r).initAtmosphereHandler(framework().getServletConfig());
    }



    @Override
    public void destroy() {
        super.destroy();
    }
}
