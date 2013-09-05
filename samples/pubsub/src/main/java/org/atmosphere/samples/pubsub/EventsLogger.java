/*
 * Copyright 2013 Jeanfrancois Arcand
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

public class EventsLogger implements AtmosphereResourceEventListener {

    public EventsLogger() {
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onPreSuspend(AtmosphereResourceEvent event) {
        System.out.println("onPreSuspend: " + event);
    }

    public void onSuspend(final AtmosphereResourceEvent event) {
        System.out.println("onSuspend: " + event);
    }

    public void onResume(AtmosphereResourceEvent event) {
        System.out.println("onResume: " + event);
    }

    public void onDisconnect(AtmosphereResourceEvent event) {
        System.out.println("onDisconnect: " + event);
    }

    public void onBroadcast(AtmosphereResourceEvent event) {
        System.out.println("onBroadcast: " + event);
    }

    public void onThrowable(AtmosphereResourceEvent event) {
        event.throwable().printStackTrace(System.err);
    }

    @Override
    public void onClose(AtmosphereResourceEvent event) {
        System.out.println("onClose: " + event);
    }
}