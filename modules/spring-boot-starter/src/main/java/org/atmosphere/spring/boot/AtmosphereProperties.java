/*
 * Copyright 2008-2026 Async-IO.org
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
package org.atmosphere.spring.boot;

import java.util.HashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "atmosphere")
public class AtmosphereProperties {

    private String servletPath = "/atmosphere/*";

    private String packages;

    private int order = 0;

    private boolean sessionSupport = false;

    private String broadcasterClass;

    private String broadcasterCacheClass;

    private Boolean websocketSupport;

    private Integer heartbeatIntervalInSeconds;

    private Map<String, String> initParams = new HashMap<>();

    public String getServletPath() {
        return servletPath;
    }

    public void setServletPath(String servletPath) {
        this.servletPath = servletPath;
    }

    public String getPackages() {
        return packages;
    }

    public void setPackages(String packages) {
        this.packages = packages;
    }

    public int getOrder() {
        return order;
    }

    public void setOrder(int order) {
        this.order = order;
    }

    public boolean isSessionSupport() {
        return sessionSupport;
    }

    public void setSessionSupport(boolean sessionSupport) {
        this.sessionSupport = sessionSupport;
    }

    public String getBroadcasterClass() {
        return broadcasterClass;
    }

    public void setBroadcasterClass(String broadcasterClass) {
        this.broadcasterClass = broadcasterClass;
    }

    public String getBroadcasterCacheClass() {
        return broadcasterCacheClass;
    }

    public void setBroadcasterCacheClass(String broadcasterCacheClass) {
        this.broadcasterCacheClass = broadcasterCacheClass;
    }

    public Boolean getWebsocketSupport() {
        return websocketSupport;
    }

    public void setWebsocketSupport(Boolean websocketSupport) {
        this.websocketSupport = websocketSupport;
    }

    public Integer getHeartbeatIntervalInSeconds() {
        return heartbeatIntervalInSeconds;
    }

    public void setHeartbeatIntervalInSeconds(Integer heartbeatIntervalInSeconds) {
        this.heartbeatIntervalInSeconds = heartbeatIntervalInSeconds;
    }

    public Map<String, String> getInitParams() {
        return initParams;
    }

    public void setInitParams(Map<String, String> initParams) {
        this.initParams = initParams;
    }
}
