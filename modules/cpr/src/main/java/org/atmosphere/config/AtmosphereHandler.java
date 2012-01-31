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

import java.util.List;

import com.thoughtworks.xstream.annotations.XStreamAlias;
import com.thoughtworks.xstream.annotations.XStreamAsAttribute;
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
@XStreamAlias("atmosphere-handler")
public class AtmosphereHandler {
	
	@XStreamAlias("support-session")
   	@XStreamAsAttribute
	private String supportSession;
	
	@XStreamAlias("context-root")
   	@XStreamAsAttribute
	private String contextRoot;
	
	@XStreamAlias("class-name")
   	@XStreamAsAttribute
	private String className;
	
	@XStreamAlias("broadcaster")
   	@XStreamAsAttribute
	private String broadcaster;
	
	@XStreamAlias("broadcasterCache")
   	@XStreamAsAttribute
	private String broadcasterCache;
	
	@XStreamAlias("broadcastFilterClasses")
   	@XStreamAsAttribute
	private String broadcastFilterClasses;
	
	@XStreamAlias("comet-support")
   	@XStreamAsAttribute
	private String cometSupport;
	
	
	@XStreamImplicit(itemFieldName="property")
	private List<AtmosphereHandlerProperty> properties;
	
	@XStreamImplicit(itemFieldName="applicationConfig")
	private List<ApplicationConfig> applicationConfig;
	
	@XStreamImplicit(itemFieldName="frameworkConfig")
	private List<FrameworkConfig> frameworkConfig;
	
	public String getSupportSession() {
		return supportSession;
	}
	public void setSupportSession(String supportSession) {
		this.supportSession = supportSession;
	}
	public String getContextRoot() {
		return contextRoot;
	}
	public void setContextRoot(String contextRoot) {
		this.contextRoot = contextRoot;
	}
	public String getClassName() {
		return className;
	}
	public void setClassName(String className) {
		this.className = className;
	}
	public String getBroadcaster() {
		return broadcaster;
	}
	public void setBroadcaster(String broadcaster) {
		this.broadcaster = broadcaster;
	}
	public List<AtmosphereHandlerProperty> getProperties() {
		return properties;
	}
	public void setProperties(List<AtmosphereHandlerProperty> properties) {
		this.properties = properties;
	}
	public List<ApplicationConfig> getApplicationConfig() {
		return applicationConfig;
	}
	public void setApplicationConfig(List<ApplicationConfig> applicationConfig) {
		this.applicationConfig = applicationConfig;
	}
	public List<FrameworkConfig> getFrameworkConfig() {
		return frameworkConfig;
	}
	public void setFrameworkConfig(List<FrameworkConfig> frameworkConfig) {
		this.frameworkConfig = frameworkConfig;
	}
	public String getBroadcasterCache() {
		return broadcasterCache;
	}
	public void setBroadcasterCache(String broadcasterCache) {
		this.broadcasterCache = broadcasterCache;
	}
	public String getBroadcastFilterClasses() {
		return broadcastFilterClasses;
	}
	public void setBroadcastFilterClasses(String broadcastFilterClasses) {
		this.broadcastFilterClasses = broadcastFilterClasses;
	}
	public String getCometSupport() {
		return cometSupport;
	}
	public void setCometSupport(String cometSupport) {
		this.cometSupport = cometSupport;
	}
	
}
