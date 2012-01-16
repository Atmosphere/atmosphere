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
package org.atmosphere.samples.di.guice;

import com.google.inject.Inject;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceEventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * @author Mathieu Carbou
 * @since 0.7
 */
public class EventsLogger implements AtmosphereResourceEventListener {

    private static final Logger logger = LoggerFactory.getLogger(EventsLogger.class);

    @Inject
    private Service service;

    public void onSuspend(final AtmosphereResourceEvent<HttpServletRequest, HttpServletResponse> event) {
        if (service == null) {
            throw new AssertionError();
        }
        logger.info("[{}] onSuspend: {}:{}",
                new Object[]{Thread.currentThread().getName(), event.getResource().getRequest().getRemoteAddr(),
                        event.getResource().getRequest().getRemotePort()});
    }

    public void onResume(AtmosphereResourceEvent<HttpServletRequest, HttpServletResponse> event) {
        if (service == null) {
            throw new AssertionError();
        }
        logger.info("[{}] onResume: {}:{}",
                new Object[]{Thread.currentThread().getName(), event.getResource().getRequest().getRemoteAddr(),
                        event.getResource().getRequest().getRemotePort()});
    }

    public void onDisconnect(AtmosphereResourceEvent<HttpServletRequest, HttpServletResponse> event) {
        if (service == null) {
            throw new AssertionError();
        }
        logger.info("[{}] onDisconnect: {}:{}",
                new Object[]{Thread.currentThread().getName(), event.getResource().getRequest().getRemoteAddr(),
                        event.getResource().getRequest().getRemotePort()});
    }

    public void onBroadcast(AtmosphereResourceEvent<HttpServletRequest, HttpServletResponse> event) {
        if (service == null) {
            throw new AssertionError();
        }
        logger.info("[{}] onBroadcast: {}:{}",
                new Object[]{Thread.currentThread().getName(), event.getResource().getRequest().getRemoteAddr(),
                        event.getResource().getRequest().getRemotePort()});
    }

    public void onThrowable(AtmosphereResourceEvent<HttpServletRequest, HttpServletResponse> event) {
        if (service == null) {
            throw new AssertionError();
        }
        logger.info("[{}] onThrowable: " + event.getResource().getRequest().getRemoteAddr() + ":" +
                event.getResource().getRequest().getRemotePort(), event.throwable());
    }

}
