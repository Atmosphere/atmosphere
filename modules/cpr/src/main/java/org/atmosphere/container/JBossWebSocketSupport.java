/*
 * Copyright 2013 Péter Miklós
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
package org.atmosphere.container;

import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.AsynchronousProcessor;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResponse;
import org.jboss.servlet.http.HttpEvent;
import org.jboss.servlet.http.HttpEventServlet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import java.io.IOException;
import java.lang.reflect.Constructor;

/**
 * JBoss AS7 websocket support based on Mike Brock's websockets implementation.
 * 
 * @author Péter Miklós
 * @see https://github.com/mikebrock/jboss-websockets
 */
public class JBossWebSocketSupport extends AsynchronousProcessor {


    private static final Logger logger = LoggerFactory.getLogger(JBossWebSocketSupport.class);
    
    private final HttpEventServlet websocketHandler;
    
    public JBossWebSocketSupport(AtmosphereConfig config) {
        super(config);
        this.websocketHandler = newWebSocketHandler(config);
    }

    /**
     * Loads the {@link JBossWebSocketHandler} u
     * 
     * @param config 
     * @return
     */
    private HttpEventServlet newWebSocketHandler(AtmosphereConfig config) {
        try {
            return new JBossWebSocketHandler(config);
        } catch (Exception e) {
            logger.error("Cannot instantiate JBossWebSocketHandler. Websocket events will not be handled.", e);
        }
        
        return null;
    }

    @Override
    public Action service(AtmosphereRequest req, AtmosphereResponse res) throws IOException, ServletException {
        return suspended(req, res);
    }

    @Override
    public boolean supportWebSocket() {
        return true;
    }

    /**
     * @param httpEvent
     * @throws javax.servlet.ServletException
     * @throws java.io.IOException
     */
    public void dispatch(HttpEvent httpEvent) throws IOException, ServletException {
        if (websocketHandler != null) {
            websocketHandler.event(httpEvent);
        }
    }

}
