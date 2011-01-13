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

import java.util.concurrent.TimeUnit;

/**
 * This class can be used to configure the life cyle of a {@link org.atmosphere.cpr.Broadcaster}, e.g when a broadcaster
 * gets destroyed {@link org.atmosphere.cpr.Broadcaster#destroy()} or when it's associated resources
 * get released {@link Broadcaster#releaseExternalResources()}.
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

    public ATMOSPHERE_RESOURCE_POLICY getLifeCyclePolicy(){
        return policy;
    }

    public TimeUnit getTimeUnit(){
        return timeUnit;
    }

    public int getTimeout(){
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
            return this;
        }

        public Builder idleTime(int time, TimeUnit timeUnit) {
            this.timeUnit = timeUnit;
            return this;
        }

        public BroadcasterLifeCyclePolicy build(){
            return new BroadcasterLifeCyclePolicy(policy, time, timeUnit);
        }
    }

}
