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
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Filter that inject Javascript code to a broadcast so it can be used with the Atmosphere JQuery Plugin.
 *
 * @author Jeanfrancois Arcand
 */
public class JavascriptClientFilter implements PerRequestBroadcastFilter {

    private final AtomicInteger uniqueScriptToken = new AtomicInteger();

    @Override
    public BroadcastAction filter(Object originalMessage, Object message) {

        if (message instanceof String) {
            StringBuilder sb = new StringBuilder("<script id=\"atmosphere_")
                    .append(uniqueScriptToken.getAndIncrement())
                    .append("\">")
                    .append("parent.callback")
                    .append("('")
                    .append(message.toString())
                    .append("');</script>");
            message = sb.toString();
        }
        return new BroadcastAction(BroadcastAction.ACTION.CONTINUE, message);
    }

    @Override
    public BroadcastAction filter(HttpServletRequest request, HttpServletResponse response, Object message) {

        if (request.getHeader("User-Agent") != null && request.getAttribute("X-Atmosphere-Transport") == null
                || request.getAttribute("X-Atmosphere-Transport") != null && ((String) request.getAttribute("X-Atmosphere-Transport")).equalsIgnoreCase("long-polling")) {
            String userAgent = request.getHeader("User-Agent").toLowerCase();
            if (userAgent != null && userAgent.startsWith("opera") && message instanceof String) {
                StringBuilder sb = new StringBuilder("<script id=\"atmosphere_")
                        .append(uniqueScriptToken.getAndIncrement())
                        .append("\">")
                        .append("window.parent.$.atmosphere.streamingCallback")
                        .append("('")
                        .append(message.toString())
                        .append("');</script>");
                message = sb.toString();
                return new BroadcastAction(BroadcastAction.ACTION.CONTINUE, message);
            }
        }
        return new BroadcastAction(BroadcastAction.ACTION.CONTINUE, message);
    }
}





