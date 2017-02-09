/*
 * Copyright 2017 Async-IO.org
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
package org.atmosphere.lifecycle;

import org.atmosphere.runtime.AtmosphereResource;
import org.atmosphere.runtime.Broadcaster;
import org.atmosphere.runtime.BroadcasterListenerAdapter;
import org.atmosphere.runtime.DefaultBroadcaster;

public class BroadcasterLifecyclePolicyHandler extends BroadcasterListenerAdapter {

    private final LifecycleHandler liferCycleHandler = new LifecycleHandler();

    /**
     * {@inheritDoc}
     */
    @Override
    public void onPostCreate(Broadcaster b) {
        if (DefaultBroadcaster.class.isAssignableFrom(b.getClass())) {
            DefaultBroadcaster broadcaster = DefaultBroadcaster.class.cast(b);
            broadcaster.lifecycleHandler(liferCycleHandler.on(broadcaster));
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onPreDestroy(Broadcaster b) {
        if (DefaultBroadcaster.class.isAssignableFrom(b.getClass())) {
            DefaultBroadcaster broadcaster = DefaultBroadcaster.class.cast(b);
            if (broadcaster.lifecycleHandler() != null) {
                broadcaster.lifecycleHandler().off(broadcaster);
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onRemoveAtmosphereResource(Broadcaster b, AtmosphereResource r) {
        if (DefaultBroadcaster.class.isAssignableFrom(b.getClass())) {
            DefaultBroadcaster broadcaster = DefaultBroadcaster.class.cast(b);
            if (broadcaster.lifecycleHandler() != null) {
                broadcaster.lifecycleHandler().offIfEmpty(broadcaster);
            }
        }
    }

}
