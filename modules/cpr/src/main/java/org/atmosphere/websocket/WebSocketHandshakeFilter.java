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
package org.atmosphere.websocket;

import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.util.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

import static org.atmosphere.cpr.HeaderConfig.X_ATMOSPHERE_ERROR;

/**
 * A Servlet Filter for configuring which WebSocket protocol version an application want to support.
 *
 * @author Jeanfrancois Arcand
 */
public class WebSocketHandshakeFilter implements Filter {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketHandshakeFilter.class);
    private String[] bannedVersion;

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        String draft = filterConfig.getInitParameter(ApplicationConfig.WEB_SOCKET_BANNED_VERSION);
        if (draft != null) {
            bannedVersion = draft.split(",");
            logger.debug("Blocked WebSocket Draft version {}", draft);
        }
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {

        HttpServletRequest r = HttpServletRequest.class.cast(request);
        if (Utils.webSocketEnabled(r)) {
            int draft = r.getIntHeader("Sec-WebSocket-Version");
            if (draft < 0) {
                draft = r.getIntHeader("Sec-WebSocket-Draft");
            }

            if (bannedVersion != null) {
                for (String s : bannedVersion) {
                    if (Integer.parseInt(s) == draft) {
                        logger.trace("Invalid WebSocket Specification {} with {} ",
                                r.getHeader("Connection"), r.getIntHeader("Sec-WebSocket-Version"));
                        HttpServletResponse.class.cast(response).addHeader(X_ATMOSPHERE_ERROR, "Websocket protocol not supported");
                        HttpServletResponse.class.cast(response).sendError(501, "Websocket protocol not supported");
                        return;
                    }
                }
            }
        }
        chain.doFilter(request, response);
    }

    @Override
    public void destroy() {
    }
}
