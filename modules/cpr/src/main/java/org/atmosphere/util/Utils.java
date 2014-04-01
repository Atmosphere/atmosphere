/*
 * Copyright 2014 Jeanfrancois Arcand
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

import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.FrameworkConfig;
import org.atmosphere.cpr.HeaderConfig;
import org.atmosphere.websocket.WebSocket;

import javax.servlet.http.HttpServletRequest;
import java.util.Enumeration;

import static org.atmosphere.cpr.HeaderConfig.WEBSOCKET_UPGRADE;

/**
 * Utils class.
 *
 * @author Jeanfrancois Arcand
 */
public final class Utils {

    public final static boolean webSocketEnabled(HttpServletRequest request) {

        boolean allowWebSocketWithoutHeaders = request.getHeader(HeaderConfig.X_ATMO_WEBSOCKET_PROXY) != null ? true : false;
        if (allowWebSocketWithoutHeaders) return true;

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

    public final static boolean firefoxWebSocketEnabled(HttpServletRequest request) {
        return webSocketEnabled(request)
                && request.getHeader(HeaderConfig.X_ATMO_PROTOCOL) != null
                && request.getHeader(HeaderConfig.X_ATMO_PROTOCOL).equals("true")
                && request.getHeader("User-Agent") != null
                && request.getHeader("User-Agent").toLowerCase().indexOf("firefox") != -1;
    }

    public final static boolean twoConnectionsTransport(AtmosphereResource.TRANSPORT t) {
        switch (t) {
            case JSONP:
            case LONG_POLLING:
            case STREAMING:
            case SSE:
            case POLLING:
            case HTMLFILE:
                return true;
            default:
                return false;
        }
    }

    public final static boolean resumableTransport(AtmosphereResource.TRANSPORT t) {
        switch (t) {
            case JSONP:
            case LONG_POLLING:
                return true;
            default:
                return false;
        }
    }

    public final static boolean pollableTransport(AtmosphereResource.TRANSPORT t) {
        switch (t) {
            case POLLING:
            case UNDEFINED:
            case CLOSE:
            case AJAX:
                return true;
            default:
                return false;
        }
    }

    public final static boolean unTrackableTransport(AtmosphereResource.TRANSPORT t) {
        switch (t) {
            case POLLING:
            case CLOSE:
            case UNDEFINED:
            case AJAX:
                return true;
            default:
                return false;
        }
    }

    public final static boolean atmosphereProtocol(AtmosphereRequest r) {
        String p = r.getHeader(HeaderConfig.X_ATMO_PROTOCOL);
        if (p != null && Boolean.valueOf(p)) {
            return true;
        }
        return false;
    }

    public final static boolean webSocketMessage(AtmosphereResource r) {
        AtmosphereRequest request = AtmosphereResourceImpl.class.cast(r).getRequest(false);
        if (request.getAttribute(FrameworkConfig.WEBSOCKET_MESSAGE) != null) {
            return true;
        }
        return false;
    }

    public static boolean properProtocol(HttpServletRequest request) {
        Enumeration<String> connection = request.getHeaders("Connection");
        if (connection == null || !connection.hasMoreElements()) {
            connection = request.getHeaders("connection");
        }

        boolean isOK = false;
        boolean isWebSocket = (request.getHeader("sec-websocket-version") != null || request.getHeader("Sec-WebSocket-Draft") != null);
        if (connection != null && connection.hasMoreElements()) {
            String[] e = connection.nextElement().toString().split(",");
            for (String upgrade : e) {
                if (upgrade.trim().equalsIgnoreCase("upgrade")) {
                    isOK = true;
                }
            }
        }
        return isWebSocket ? isOK : true;
    }
}
