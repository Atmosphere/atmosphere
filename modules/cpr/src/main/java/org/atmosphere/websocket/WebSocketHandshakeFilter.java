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
package org.atmosphere.websocket;

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
 */
public class WebSocketHandshakeFilter implements Filter {

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {

        if (HttpServletRequest.class.cast(request).getHeader("Upgrade") != null) {
            int draft = HttpServletRequest.class.cast(request).getIntHeader("Sec-WebSocket-Version");
            if (draft < 0) {
                draft = HttpServletRequest.class.cast(request).getIntHeader("Sec-WebSocket-Draft");
            }

            switch (draft) {
                case -1:
                case 0:
                    HttpServletResponse.class.cast(response).addHeader(X_ATMOSPHERE_ERROR, "Websocket protocol not supported");
                    HttpServletResponse.class.cast(response).sendError(202, "Websocket protocol not supported");
                    break;
                default:
                    chain.doFilter(request, response);
                    break;
            }
        } else {
            chain.doFilter(request, response);
        }
    }

    @Override
    public void destroy() {
    }
}
