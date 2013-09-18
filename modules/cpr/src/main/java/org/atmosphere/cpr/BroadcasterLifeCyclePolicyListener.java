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
package org.atmosphere.cpr;

/**
 * Simple listener to be used to track {@link BroadcasterLifeCyclePolicy} events.
 *
 * @author Jeanfrancois Arcand
 */
public interface BroadcasterLifeCyclePolicyListener {

    /**
     * Invoked when a {@link Broadcaster}'s list of {@link AtmosphereResource} becomes empty, eg.
     * the broadcaster has no longer resources associated with it.
     */
    void onEmpty();

    /**
     * Invoke when a {@link Broadcaster} has no activity.
     */
    void onIdle();

    /**
     * Both {@link org.atmosphere.cpr.Broadcaster#releaseExternalResources()} and {@link org.atmosphere.cpr.Broadcaster#destroy()}
     * are about to be invoked.
     */
    void onDestroy();
}
