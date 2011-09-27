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
package org.atmosphere.samples.pubsub;

import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceEventListener;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class EventsLogger implements AtmosphereResourceEventListener {

    public EventsLogger() {
    }


    public void onSuspend(final AtmosphereResourceEvent<HttpServletRequest, HttpServletResponse> event) {
        System.out.println("onSuspend: " + event.getResource().getRequest().getRemoteAddr()
                + event.getResource().getRequest().getRemotePort());
    }

    public void onResume(AtmosphereResourceEvent<HttpServletRequest, HttpServletResponse> event) {
        System.out.println("onResume: " + event.getResource().getRequest().getRemoteAddr());
    }

    public void onDisconnect(AtmosphereResourceEvent<HttpServletRequest, HttpServletResponse> event) {
        System.out.println("onDisconnect: " + event.getResource().getRequest().getRemoteAddr()
                + event.getResource().getRequest().getRemotePort());
    }

    public void onBroadcast(AtmosphereResourceEvent<HttpServletRequest, HttpServletResponse> event) {
        System.out.println("onBroadcast: " + event.getMessage());
    }

    public void onThrowable(AtmosphereResourceEvent<HttpServletRequest, HttpServletResponse> event) {
        event.throwable().printStackTrace(System.err);
    }
}