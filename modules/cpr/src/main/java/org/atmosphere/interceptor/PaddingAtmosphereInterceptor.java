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
import org.atmosphere.cpr.AsyncIOInterceptorAdapter;
import org.atmosphere.cpr.AsyncIOWriter;
import org.atmosphere.cpr.AtmosphereInterceptorAdapter;
import org.atmosphere.cpr.AtmosphereInterceptorWriter;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResource.TRANSPORT;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceEventListenerAdapter;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.FrameworkConfig;
import org.atmosphere.cpr.HeaderConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Padding interceptor for Browser that needs whitespace when streaming is used.
 *
 * @author Jeanfrancois Arcand
 */
public class PaddingAtmosphereInterceptor extends AtmosphereInterceptorAdapter {

    private static final Logger logger = LoggerFactory.getLogger(PaddingAtmosphereInterceptor.class);

    private static final byte[] padding;
    private static final String paddingText;

    static {
        StringBuilder whitespace = new StringBuilder();
        for (int i = 0; i < 8192; i++) {
            whitespace.append(" ");
        }
        whitespace.append("\n");
        paddingText = whitespace.toString();
        padding = paddingText.getBytes();
    }

    private void writePadding(AtmosphereResponse response) {
        AtmosphereRequest request = response.request();
        if (request != null && request.getAttribute("paddingWritten") != null) return;

        if (response.resource().transport().equals(TRANSPORT.STREAMING)) {
            request.setAttribute(FrameworkConfig.TRANSPORT_IN_USE, HeaderConfig.STREAMING_TRANSPORT);
            response.setContentType("text/plain");
        }

        response.write(padding, true);
        try {
            response.flushBuffer();
        } catch (IOException e) {
            logger.trace("", e);
        }
    }

    @Override
    public Action inspect(final AtmosphereResource r) {
        final AtmosphereResponse response = r.getResponse();

        if (r.transport().equals(TRANSPORT.STREAMING) || r.transport().equals(TRANSPORT.LONG_POLLING)) {
            r.addEventListener(new ForcePreSuspend(response));

            super.inspect(r);

            AsyncIOWriter writer = response.getAsyncIOWriter();
            if (AtmosphereInterceptorWriter.class.isAssignableFrom(writer.getClass())) {
                AtmosphereInterceptorWriter.class.cast(writer).interceptor(new AsyncIOInterceptorAdapter() {
                    private void padding() {
                        if (!r.isSuspended()) {
                            writePadding(response);
                            r.getRequest().setAttribute("paddingWritten", "true");
                        }
                    }

                    @Override
                    public void prePayload(AtmosphereResponse response, byte[] data, int offset, int length) {
                        padding();
                    }

                    @Override
                    public void postPayload(AtmosphereResponse response, byte[] data, int offset, int length) {
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
        return "Browser Padding Interceptor Support";
    }

    public final class ForcePreSuspend extends AtmosphereResourceEventListenerAdapter implements AllowInterceptor {

        private final AtmosphereResponse response;

        public ForcePreSuspend(AtmosphereResponse response) {
            this.response = response;
        }

        @Override
        public void onPreSuspend(AtmosphereResourceEvent event) {
            writePadding(response);
        }
    }
}
