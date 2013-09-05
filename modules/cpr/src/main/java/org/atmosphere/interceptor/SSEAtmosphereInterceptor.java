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
package org.atmosphere.interceptor;

import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AsyncIOInterceptorAdapter;
import org.atmosphere.cpr.AsyncIOWriter;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereInterceptorAdapter;
import org.atmosphere.cpr.AtmosphereInterceptorWriter;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceEventListenerAdapter;
import org.atmosphere.cpr.AtmosphereResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;

import static org.atmosphere.cpr.ApplicationConfig.PROPERTY_USE_STREAM;
import static org.atmosphere.cpr.FrameworkConfig.CALLBACK_JAVASCRIPT_PROTOCOL;
import static org.atmosphere.cpr.FrameworkConfig.CONTAINER_RESPONSE;

/**
 * HTML 5 Server Side Events implementation.
 *
 * @author Jeanfrancois Arcand
 */
public class SSEAtmosphereInterceptor extends AtmosphereInterceptorAdapter {

    private static final Logger logger = LoggerFactory.getLogger(SSEAtmosphereInterceptor.class);

    private static final byte[] padding;
    private static final String paddingText;
    private static final byte[] END = "\n\n".getBytes();
    private String contentType = "text/event-stream";

    static {
        StringBuffer whitespace = new StringBuffer();
        for (int i = 0; i < 2000; i++) {
            whitespace.append(" ");
        }
        whitespace.append("\n");
        paddingText = whitespace.toString();
        padding = paddingText.getBytes();
    }

    @Override
    public void configure(AtmosphereConfig config) {
        String s = config.getInitParameter(ApplicationConfig.SSE_DEFAULT_CONTENTTYPE);
        if (s != null) {
            contentType = s;
        }
    }

    private boolean writePadding(AtmosphereResponse response) {
        if (response.request() != null && response.request().getAttribute("paddingWritten") != null) return false;

        response.setContentType(contentType);
        response.setCharacterEncoding("utf-8");
        boolean isUsingStream = (Boolean) response.request().getAttribute(PROPERTY_USE_STREAM);
        if (isUsingStream) {
            OutputStream stream = null;
            try {
                stream = response.getResponse().getOutputStream();
            } catch (IOException e) {
                logger.trace("", e);
            }

            try {
                stream.write(padding);
                stream.flush();
            } catch (IOException ex) {
                logger.warn("SSE may not work", ex);
            }
        } else {
            PrintWriter w = null;
            try {
                w = response.getResponse().getWriter();
            } catch (IOException e) {
                logger.trace("", e);
            }

            w.println(paddingText);
            w.flush();
        }
        response.resource().getRequest().setAttribute("paddingWritten", "true");
        return true;
    }

    private final class P extends AtmosphereResourceEventListenerAdapter implements AllowInterceptor {

        private final AtmosphereResponse response;

        private P(AtmosphereResponse response) {
            this.response = response;
        }

        @Override
        public void onPreSuspend(AtmosphereResourceEvent event) {
            writePadding(response);
        }
    }

    @Override
    public Action inspect(final AtmosphereResource r) {
        final AtmosphereResponse response = r.getResponse();

        if (r.transport().equals(AtmosphereResource.TRANSPORT.SSE)) {
            super.inspect(r);

            r.addEventListener(new P(response));

            AsyncIOWriter writer = response.getAsyncIOWriter();
            if (AtmosphereInterceptorWriter.class.isAssignableFrom(writer.getClass())) {
                AtmosphereInterceptorWriter.class.cast(writer).interceptor(new AsyncIOInterceptorAdapter() {
                    private boolean padding() {
                        if (!r.isSuspended()) {
                            return writePadding(response);
                        }
                        return false;
                    }

                    @Override
                    public void prePayload(AtmosphereResponse response, byte[] data, int offset, int length) {
                        boolean noPadding = padding();
                        // The CALLBACK_JAVASCRIPT_PROTOCOL may be called by a framework running on top of Atmosphere
                        // In that case, we must pad/protocol indenendently of the state of the AtmosphereResource
                        if (!noPadding || r.getRequest().getAttribute(CALLBACK_JAVASCRIPT_PROTOCOL) != null) {
                            response.write("data:", true);
                        }
                    }

                    @Override
                    public void postPayload(AtmosphereResponse response, byte[] data, int offset, int length) {
                        // The CALLBACK_JAVASCRIPT_PROTOCOL may be called by a framework running on top of Atmosphere
                        // In that case, we must pad/protocol indenendently of the state of the AtmosphereResource
                        if (r.isSuspended() || r.getRequest().getAttribute(CALLBACK_JAVASCRIPT_PROTOCOL) != null
                                || r.getRequest().getAttribute(CONTAINER_RESPONSE) != null) {
                            response.write(END, true);
                        }

                        /**
                         * When used with https://github.com/remy/polyfills/blob/master/EventSource.js , we
                         * resume after every message.
                         */
                        String ua = r.getRequest().getHeader("User-Agent");
                        if (ua != null && ua.contains("MSIE")) {
                            try {
                                response.flushBuffer();
                            } catch (IOException e) {
                                logger.trace("", e);
                            }
                            r.resume();
                        }
                    }
                });
            } else {
                logger.warn("Unable to apply {}. Your AsyncIOWriter must implement {}", getClass().getName(), AtmosphereInterceptorWriter.class.getName());
            }
        }
        return Action.CONTINUE;
    }

    @Override
    public String toString() {
        return "SSE Interceptor Support";
    }
}
