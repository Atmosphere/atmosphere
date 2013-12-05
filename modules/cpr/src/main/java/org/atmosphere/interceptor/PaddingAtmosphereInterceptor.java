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

import static org.atmosphere.cpr.FrameworkConfig.INJECTED_ATMOSPHERE_RESOURCE;

/**
 * Padding interceptor for Browser that needs whitespace when streaming is used.
 *
 * @author Jeanfrancois Arcand
 */
public class PaddingAtmosphereInterceptor extends AtmosphereInterceptorAdapter {

    private static final Logger logger = LoggerFactory.getLogger(PaddingAtmosphereInterceptor.class);

    private final byte[] padding;
    private final String paddingText;

    public PaddingAtmosphereInterceptor(){
        paddingText = confPadding(2048);
        padding = paddingText.getBytes();
    }

    public PaddingAtmosphereInterceptor(int size){
        paddingText = confPadding(size);
        padding = paddingText.getBytes();
    }

    protected final static String confPadding(int size) {
        StringBuilder whitespace = new StringBuilder();
        for (int i = 0; i < size; i++) {
            whitespace.append(" ");
        }
        whitespace.append("\n");
        return whitespace.toString();
    }

    private void writePadding(AtmosphereResponse response) {
        AtmosphereRequest request = response.request();
        if (request != null && request.getAttribute("paddingWritten") != null) return;

        if (response.resource() != null && response.resource().transport().equals(TRANSPORT.STREAMING)) {
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
        final AtmosphereRequest request = r.getRequest();

        String uuid = request.getHeader(HeaderConfig.X_ATMOSPHERE_TRACKING_ID);
        boolean padding = r.transport().equals(TRANSPORT.STREAMING) || r.transport().equals(TRANSPORT.LONG_POLLING);
        if (uuid != null
                && !uuid.equals("0")
                && r.transport().equals(TRANSPORT.WEBSOCKET)
                && request.getAttribute(INJECTED_ATMOSPHERE_RESOURCE) != null) {
            padding = true;
        }

        if (padding) {
            r.addEventListener(new ForcePreSuspend(response));

            super.inspect(r);

            AsyncIOWriter writer = response.getAsyncIOWriter();
            if (AtmosphereInterceptorWriter.class.isAssignableFrom(writer.getClass())) {
                AtmosphereInterceptorWriter.class.cast(writer).interceptor(new AsyncIOInterceptorAdapter() {
                    private void padding() {
                        if (!r.isSuspended()) {
                            writePadding(response);
                            request.setAttribute("paddingWritten", "true");
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
