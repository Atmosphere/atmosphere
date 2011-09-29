/*
 * Copyright 2011 Jeanfrancois Arcand
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

import java.util.concurrent.TimeUnit;

/**
 * This class can be used to configure the life cycle of a {@link org.atmosphere.cpr.Broadcaster}, e.g when a broadcaster
 * gets destroyed {@link org.atmosphere.cpr.Broadcaster#destroy()} or when it's associated resources
 * get released {@link Broadcaster#releaseExternalResources()}.
 *
 * @author Jeanfrancois Arcand
 */
public class BroadcasterLifeCyclePolicy {

    public enum ATMOSPHERE_RESOURCE_POLICY {

        /**
         * Release all resources associated with the Broadcaster when the idle time expires.
         * {@link org.atmosphere.cpr.Broadcaster#releaseExternalResources()} will be invoked.
         */
        IDLE,

        /**
         * Release all resources associated with the Broadcaster when the idle time expires, release all resources,
         * and destroy the Broadcaster. This operation remove the Broadcaster from it's associated {@link org.atmosphere.cpr.BroadcasterFactory}
         * Invoke {@link org.atmosphere.cpr.Broadcaster#destroy()} will be invoked
         */
        IDLE_DESTROY,

        /**
         * If there is no {@link org.atmosphere.cpr.AtmosphereResource} associated with the Broadcaster,
         * release all resources.
         * {@link org.atmosphere.cpr.Broadcaster#releaseExternalResources()} will be invoked.
         */
        EMPTY,
        /**
         * If there is no {@link org.atmosphere.cpr.AtmosphereResource} associated with the Broadcaster, release all resources,
         * and destroy the broadcaster. This operation remove the Broadcaster from it's associated {@link org.atmosphere.cpr.BroadcasterFactory}
         * Invoke {@link org.atmosphere.cpr.Broadcaster#destroy()} will be invoked
         */
        EMPTY_DESTROY,

        /**
         * Never release or destroy the {@link org.atmosphere.cpr.Broadcaster}.
         */
        NEVER

    }

    private final ATMOSPHERE_RESOURCE_POLICY policy;
    private final int time;
    private final TimeUnit timeUnit;

    private BroadcasterLifeCyclePolicy(ATMOSPHERE_RESOURCE_POLICY policy, int time, TimeUnit timeUnit) {
        this.policy = policy;
        this.time = time;
        this.timeUnit = timeUnit;
    }

    private BroadcasterLifeCyclePolicy(ATMOSPHERE_RESOURCE_POLICY policy) {
        this.policy = policy;
        this.time = -1;
        this.timeUnit = null;
    }

    public ATMOSPHERE_RESOURCE_POLICY getLifeCyclePolicy() {
        return policy;
    }

    public TimeUnit getTimeUnit() {
        return timeUnit;
    }

    public int getTimeout() {
        return time;
    }

    public static final class Builder {

        private ATMOSPHERE_RESOURCE_POLICY policy;
        private int time;
        private TimeUnit timeUnit;

        public Builder policy(ATMOSPHERE_RESOURCE_POLICY policy) {
            this.policy = policy;
            return this;
        }

        public Builder idleTimeInMS(int time) {
            timeUnit = TimeUnit.MILLISECONDS;
            this.time = time;
            return this;
        }

        public Builder idleTime(int time, TimeUnit timeUnit) {
            this.timeUnit = timeUnit;
            this.time = time;
            return this;
        }

        public BroadcasterLifeCyclePolicy build() {
            return new BroadcasterLifeCyclePolicy(policy, time, timeUnit);
        }
    }

}
