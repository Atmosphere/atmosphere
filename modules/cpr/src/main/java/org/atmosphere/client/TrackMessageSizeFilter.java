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
package org.atmosphere.client;

import org.atmosphere.cpr.PerRequestBroadcastFilter;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class TrackMessageSizeFilter implements PerRequestBroadcastFilter{

    @Override
    public BroadcastAction filter(HttpServletRequest request, HttpServletResponse response, Object message) {

        if (request.getHeader("X-Atmosphere-TrackMessageSize").equalsIgnoreCase("true") && String.class.isAssignableFrom(message.getClass())) {
            String msg = message.toString();
            msg = "!?" + msg.length() + "!?" + msg;
            return new BroadcastAction(BroadcastAction.ACTION.CONTINUE, msg);

        }
        return new BroadcastAction(BroadcastAction.ACTION.CONTINUE, message);
    }

    @Override
    public BroadcastAction filter(Object originalMessage, Object message) {
        return new BroadcastAction(message);
    }
}
