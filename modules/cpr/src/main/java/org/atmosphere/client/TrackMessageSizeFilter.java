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
package org.atmosphere.client;

import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.BroadcastFilter;
import org.atmosphere.cpr.PerRequestBroadcastFilter;

import static org.atmosphere.cpr.HeaderConfig.X_ATMOSPHERE_TRACKMESSAGESIZE;

/**
 * A {@link PerRequestBroadcastFilter} implementation that add the expected length of the message. This is
 * useful when used with the atmosphere.js library as the library will read the expected size and wait for the
 * entire messages to be received before invoking its associated callback.
 *
 * NOTE: The broadcasted message MUST BE a String. If your application is broadcasting another object, you need to
 * write your own Filter.
 *
 * If you aren't using atmosphere.js, you need to add the {@link org.atmosphere.cpr.HeaderConfig#X_ATMOSPHERE_TRACKMESSAGESIZE} header in order to
 * enable that Filter. The delimiter character used is '|'.
 *
 * For example, broadcasting String 'helloword' will be received by the client as '9|helloword' but delivered as 'helloword'
 * to the Javascript function/callback.
 */
public class TrackMessageSizeFilter implements PerRequestBroadcastFilter {

    @Override
    public BroadcastAction filter(AtmosphereResource r, Object originalMessage, Object message) {

        AtmosphereRequest request = r.getRequest();
        if (r.uuid().equals(BroadcastFilter.VOID_ATMOSPHERE_RESOURCE_UUID) || "true".equalsIgnoreCase(request.getHeader(X_ATMOSPHERE_TRACKMESSAGESIZE))
                && message != null && String.class.isAssignableFrom(message.getClass())) {

            String msg = message.toString().trim();
            msg = msg.length() + "|" + msg;
            return new BroadcastAction(BroadcastAction.ACTION.CONTINUE, msg);

        }
        return new BroadcastAction(BroadcastAction.ACTION.CONTINUE, message);
    }

    @Override
    public BroadcastAction filter(Object originalMessage, Object message) {
        return new BroadcastAction(message);
    }
}
