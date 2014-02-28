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
package org.atmosphere.util;

import javax.servlet.http.HttpServletRequest;
import java.util.Enumeration;

import static org.atmosphere.cpr.HeaderConfig.WEBSOCKET_UPGRADE;

/**
 * Utils class.
 *
 * @author Jeanfrancois Arcand
 */
public final class Utils {

    public static boolean webSocketEnabled(HttpServletRequest request) {
        boolean webSocketEnabled = false;
        Enumeration<String> connection = request.getHeaders("Connection");
        if (connection == null || !connection.hasMoreElements()) {
            connection = request.getHeaders("connection");
        }

        if (connection != null && connection.hasMoreElements()) {
            String[] e = connection.nextElement().toString().split(",");
            for (String upgrade : e) {
                if (upgrade.trim().equalsIgnoreCase(WEBSOCKET_UPGRADE)) {
                    webSocketEnabled = true;
                    break;
                }
            }
        }
        return webSocketEnabled;
    }

    public static boolean isWebSocketProxied(HttpServletRequest request) {
        boolean proxied = false;
        Enumeration<String> connection = request.getHeaders("Connection");
        if (connection == null || !connection.hasMoreElements()) {
            connection = request.getHeaders("connection");
        }

        boolean isWebSocket = (request.getHeader("sec-websocket-version") != null || request.getHeader("Sec-WebSocket-Draft") != null);
        if (connection != null && connection.hasMoreElements()) {
            String[] e = connection.nextElement().toString().split(",");
            for (String upgrade : e) {
                if (upgrade.trim().equalsIgnoreCase("keep-Alive") && isWebSocket) {
                    proxied = true;
                    break;
                }
            }
        }
        return proxied;
    }

}
