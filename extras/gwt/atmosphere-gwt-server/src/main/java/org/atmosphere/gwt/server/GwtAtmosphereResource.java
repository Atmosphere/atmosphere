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
package org.atmosphere.gwt.server;

import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.Broadcaster;

import javax.servlet.http.HttpSession;
import java.io.Serializable;
import java.util.List;
import javax.servlet.ServletContext;

public interface GwtAtmosphereResource {
    public Broadcaster getBroadcaster();

    public ServletContext getServletContext();
    
    public HttpSession getSession();

    public HttpSession getSession(boolean create);

    public int getHeartBeatInterval();

    public void post(Object message);

    public void post(List<?> messages);

    public AtmosphereResource getAtmosphereResource();

    public AtmosphereRequest getRequest();

    public AtmosphereResponse getResponse();

    public boolean isAlive();

    public <T> void setAttribute(String name, T value);

    public <T> T getAttribute(String name);

    public int getConnectionID();
    
    public boolean isSystemMessage(Serializable message);
}
