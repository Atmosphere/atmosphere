/*
 * Copyright 2012 Sebastien Dionne
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
package org.atmosphere.config;

import static org.atmosphere.cpr.ApplicationConfig.DEFAULT_NAMED_DISPATCHER;

import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;

import org.atmosphere.cpr.AtmosphereServlet;
import org.atmosphere.cpr.BroadcasterFactory;
import org.atmosphere.cpr.DefaultBroadcasterFactory;
import org.atmosphere.cpr.AtmosphereServlet.AtmosphereHandlerWrapper;

import com.thoughtworks.xstream.annotations.XStreamAlias;
import com.thoughtworks.xstream.annotations.XStreamImplicit;

/*
 * Copyright 2012 Sebastien Dionne
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
@XStreamAlias("atmosphere-handlers")
public class AtmosphereConfig {
	
	@XStreamAlias(impl=AtmosphereHandler.class, value="atmosphere-handler")
	@XStreamImplicit(itemFieldName="atmosphere-handler")
	private List<AtmosphereHandler> atmosphereHandler;
	
	private boolean supportSession = true;
    private BroadcasterFactory broadcasterFactory;
    private String dispatcherName = DEFAULT_NAMED_DISPATCHER;
	private AtmosphereServlet atmosphereServlet = null;
	
	// for custom properties 
	private Map<String, Object> properties = new HashMap<String, Object>();

	public List<AtmosphereHandler> getAtmosphereHandler() {
		return atmosphereHandler;
	}

	public void setAtmosphereHandler(List<AtmosphereHandler> atmosphereHandler) {
		this.atmosphereHandler = atmosphereHandler;
	}
	
	public AtmosphereServlet getAtmosphereServlet() {
		return atmosphereServlet;
	}
	
	public void setAtmosphereServlet(AtmosphereServlet atmosphereServlet) {
		this.atmosphereServlet = atmosphereServlet;
	}

	public ServletConfig getServletConfig() {
        return atmosphereServlet.getServletConfig();
    }
	
	public ServletContext getServletContext() {
        return atmosphereServlet.getServletContext();
    }
	
	public String getWebServerName() {
        return atmosphereServlet.getCometSupport().getContainerName();
    }
	
	public Map<String, AtmosphereHandlerWrapper> handlers() {
        return atmosphereServlet.getAtmosphereHandlers();
    }
	
	public String getInitParameter(String name) {
        return atmosphereServlet.getInitParameter(name);
    }

    public Enumeration<String> getInitParameterNames() {
        return atmosphereServlet.getInitParameterNames();
    }

	public boolean isSupportSession() {
		return supportSession;
	}

	public void setSupportSession(boolean supportSession) {
		this.supportSession = supportSession;
	}
	
	/**
     * Return an instance of a {@link DefaultBroadcasterFactory}
     *
     * @return an instance of a {@link DefaultBroadcasterFactory}
     */
	public BroadcasterFactory getBroadcasterFactory() {
		return broadcasterFactory;
	}

	public void setBroadcasterFactory(BroadcasterFactory broadcasterFactory) {
		this.broadcasterFactory = broadcasterFactory;
	}

	public String getDispatcherName() {
		return dispatcherName;
	}

	public void setDispatcherName(String dispatcherName) {
		this.dispatcherName = dispatcherName;
	}
	
	public Map<String, Object> properties() {
        return properties;
    }
}
