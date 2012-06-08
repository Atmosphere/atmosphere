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

import java.util.ArrayList;
import java.util.List;

/**
 * @author Sebastien Dionne : sebastien.dionne@gmail.com
 */
public class AtmosphereHandlerConfig {

    // "" means unset
    private String supportSession = "";
    private String contextRoot;
    private String className;
    private String broadcaster;
    private String broadcasterCache;
    private String cometSupport;

    private List<String> broadcastFilterClasses = new ArrayList<String>();
    private List<String> interceptors = new ArrayList<String>();
    private List<AtmosphereHandlerProperty> properties = new ArrayList<AtmosphereHandlerProperty>();
    private List<ApplicationConfiguration> applicationConfig = new ArrayList<ApplicationConfiguration>();
    private List<FrameworkConfiguration> frameworkConfig = new ArrayList<FrameworkConfiguration>();

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

    public List<ApplicationConfiguration> getApplicationConfig() {
        return applicationConfig;
    }

    public void setApplicationConfig(List<ApplicationConfiguration> applicationConfig) {
        this.applicationConfig = applicationConfig;
    }

    public List<FrameworkConfiguration> getFrameworkConfig() {
        return frameworkConfig;
    }

    public void setFrameworkConfig(List<FrameworkConfiguration> frameworkConfig) {
        this.frameworkConfig = frameworkConfig;
    }

    public String getBroadcasterCache() {
        return broadcasterCache;
    }

    public void setBroadcasterCache(String broadcasterCache) {
        this.broadcasterCache = broadcasterCache;
    }

    public List<String> getBroadcastFilterClasses() {
        return broadcastFilterClasses;
    }

    public void setAtmosphereInterceptorClasses(List<String> interceptors) {
        this.interceptors = interceptors;
    }

    public List<String> getAtmosphereInterceptorClasses() {
        return interceptors;
    }

    public void setBroadcastFilterClasses(List<String> broadcastFilterClasses) {
        this.broadcastFilterClasses = broadcastFilterClasses;
    }

    public String getCometSupport() {
        return cometSupport;
    }

    public void setCometSupport(String cometSupport) {
        this.cometSupport = cometSupport;
    }

}
