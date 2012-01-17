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

import org.atmosphere.cpr.FrameworkConfig;
import org.atmosphere.cpr.HeaderConfig;
import org.atmosphere.cpr.PerRequestBroadcastFilter;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * A {@link org.atmosphere.cpr.BroadcastFilter} that add support for jQuery.atmosphere.js JSONP_TRANSPORT support.
 *
 * @author Jeanfrancois Arcand
 */
public class JSONPTransportFilter implements PerRequestBroadcastFilter {
    @Override
    public BroadcastAction filter(HttpServletRequest request, HttpServletResponse response, Object message) {

        String s = request.getParameter(HeaderConfig.JSONP_CALLBACK_NAME);
        if (s != null) {
            String contentType = response.getContentType();
            if (contentType == null) {
                contentType = (String) request.getAttribute(FrameworkConfig.EXPECTED_CONTENT_TYPE);
            }

            if (contentType != null && !contentType.contains("json")) {
                String jsonPMessage = s + "({\"message\" : \"" + message + "\"})";
                return new BroadcastAction(jsonPMessage);
            } else {
                String jsonPMessage = s + "({\"message\" :" + message + "})";
                return new BroadcastAction(jsonPMessage);
            }
        }

        return new BroadcastAction(message);
    }

    @Override
    public BroadcastAction filter(Object originalMessage, Object message) {
        return new BroadcastAction(message);
    }
}
