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

public class WebSocketHandshakeFilter implements Filter {
    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        // TODO: add configuration
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
