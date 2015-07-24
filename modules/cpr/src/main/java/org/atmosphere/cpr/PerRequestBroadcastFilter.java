/*
 * Copyright 2015 Async-IO.org
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
 * An extended {@link BroadcastFilter} that can be used to filter based on {@link AtmosphereResource}.
 *
 * @author Jean-francois Arcand
 */
public interface PerRequestBroadcastFilter extends BroadcastFilter {

    /**
     * Transform or filter a message per {@link AtmosphereResource}. Be careful when setting headers on the
     * {@link AtmosphereResponse} as the headers may have been already sent back to the browser.
     *
     *
     * @param broadcasterId the {@link org.atmosphere.cpr.Broadcaster#getID()}
     * @param r the {@link org.atmosphere.cpr.AtmosphereResource}
     * @param originalMessage The original message used when calling {@link org.atmosphere.cpr.Broadcaster#broadcast(Object)}
     * @param message         a message
     * @return a {@link BroadcastAction}
     */
    BroadcastAction filter(String broadcasterId, AtmosphereResource r, Object originalMessage, Object message);
}
