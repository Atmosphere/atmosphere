/*
 * Copyright 2012 Jeanfrancois Arcand
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

import org.atmosphere.cpr.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;

import static org.atmosphere.cpr.ApplicationConfig.PROPERTY_USE_STREAM;

/**
 * HTML 5 Server Side Events implementation.
 *
 * @author Jeanfrancois Arcand
 */
public class SSEAtmosphereInterceptor extends AtmosphereInterceptorAdapter {

    private static final Logger logger = LoggerFactory.getLogger(SSEAtmosphereInterceptor.class);

    private static final byte[] padding;
    private static final String paddingText;

    static {
        StringBuffer whitespace = new StringBuffer();
        for (int i = 0; i < 2000; i++) {
            whitespace.append(" ");
        }
        whitespace.append("\n");
        paddingText = whitespace.toString();
        padding = paddingText.getBytes();
    }

    private void writePadding(AtmosphereResponse response) {
        if (response.request() != null && response.request().getAttribute("paddingWritten") != null) return;

        response.setContentType("text/event-stream");
        response.setCharacterEncoding("utf-8");
        boolean isUsingStream = (Boolean) response.request().getAttribute(PROPERTY_USE_STREAM);
        if (isUsingStream) {
            OutputStream stream = null;
            try {
                stream = response.getOutputStream();
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
                w = response.getWriter();
            } catch (IOException e) {
                logger.trace("", e);
            }

            w.println(padding);
            w.flush();
        }
    }

    @Override
    public Action inspect(final AtmosphereResource r) {
        final AtmosphereResponse response = r.getResponse();

        if (r.transport().equals(AtmosphereResource.TRANSPORT.SSE)) {
            super.inspect(r);

            r.addEventListener(new AtmosphereResourceEventListenerAdapter() {
                @Override
                public void onSuspend(AtmosphereResourceEvent event) {
                    writePadding(response);
                }
            });

            AsyncIOWriter writer = response.getAsyncIOWriter();
            if (AtmosphereInterceptorWriter.class.isAssignableFrom(writer.getClass())) {
                AtmosphereInterceptorWriter.class.cast(writer).interceptor(new AsyncIOInterceptor() {
                    private void padding() {
                        if (!r.isSuspended()) {
                            writePadding(response);
                            r.getRequest().setAttribute("paddingWritten", "true");
                        }
                    }

                    @Override
                    public void prePayload(AtmosphereResponse response, String data) {
                        padding();
                        response.write("data:");
                    }

                    @Override
                    public void prePayload(AtmosphereResponse response, byte[] data) {
                        padding();
                        response.write("data:");
                    }

                    @Override
                    public void prePayload(AtmosphereResponse response, byte[] data, int offset, int length) {
                        padding();
                        response.write("data:");
                    }

                    @Override
                    public byte[] transformPayload(String responseDraft, String data) throws IOException {
                        return responseDraft.getBytes();
                    }

                    @Override
                    public byte[] transformPayload(byte[] responseDraft, byte[] data) throws IOException {
                        return responseDraft;
                    }

                    @Override
                    public byte[] transformPayload(byte[] responseDraft, byte[] data, int offset, int length) {
                        return responseDraft;
                    }

                    @Override
                    public void postPayload(AtmosphereResponse response, String data) {
                        response.write("\n\n");
                    }

                    @Override
                    public void postPayload(AtmosphereResponse response, byte[] data) {
                        response.write("\n\n".getBytes());
                    }

                    @Override
                    public void postPayload(AtmosphereResponse response, byte[] data, int offset, int length) {
                        response.write("\n\n".getBytes());
                    }
                });
            } else {
                throw new IllegalStateException("AsyncIOWriter must be an instance of " + AsyncIOWriter.class.getName());
            }
        }
        return Action.CONTINUE;
    }

    @Override
    public String toString() {
        return "SSE Interceptor Support";
    }
}
