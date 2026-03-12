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

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;

@ConfigurationProperties(prefix = "atmosphere")
public class AtmosphereProperties {

    private String servletPath = "/atmosphere/*";

    private String packages;

    private int order = 0;

    private boolean sessionSupport = false;

    private String broadcasterClass;

    private String broadcasterCacheClass;

    private Boolean websocketSupport;

    @DurationUnit(ChronoUnit.SECONDS)
    private Duration heartbeatInterval;

    private DurableSessionsProperties durableSessions = new DurableSessionsProperties();

    private Map<String, String> initParams = new HashMap<>();

    private GrpcProperties grpc = new GrpcProperties();

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

    public Duration getHeartbeatInterval() {
        return heartbeatInterval;
    }

    public void setHeartbeatInterval(Duration heartbeatInterval) {
        this.heartbeatInterval = heartbeatInterval;
    }

    public Map<String, String> getInitParams() {
        return initParams;
    }

    public void setInitParams(Map<String, String> initParams) {
        this.initParams = initParams;
    }

    public DurableSessionsProperties getDurableSessions() {
        return durableSessions;
    }

    public void setDurableSessions(DurableSessionsProperties durableSessions) {
        this.durableSessions = durableSessions;
    }

    public GrpcProperties getGrpc() {
        return grpc;
    }

    public void setGrpc(GrpcProperties grpc) {
        this.grpc = grpc;
    }

    public static class GrpcProperties {

        private boolean enabled = false;

        private int port = 9090;

        private boolean enableReflection = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public boolean isEnableReflection() {
            return enableReflection;
        }

        public void setEnableReflection(boolean enableReflection) {
            this.enableReflection = enableReflection;
        }
    }

    public static class DurableSessionsProperties {

        private boolean enabled = false;

        @DurationUnit(ChronoUnit.MINUTES)
        private Duration sessionTtl = Duration.ofMinutes(1440);

        @DurationUnit(ChronoUnit.SECONDS)
        private Duration cleanupInterval = Duration.ofSeconds(60);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Duration getSessionTtl() {
            return sessionTtl;
        }

        public void setSessionTtl(Duration sessionTtl) {
            this.sessionTtl = sessionTtl;
        }

        public Duration getCleanupInterval() {
            return cleanupInterval;
        }

        public void setCleanupInterval(Duration cleanupInterval) {
            this.cleanupInterval = cleanupInterval;
        }
    }
}
