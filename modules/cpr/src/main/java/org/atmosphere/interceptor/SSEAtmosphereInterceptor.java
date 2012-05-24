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

import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.AsyncIOWriter;
import org.atmosphere.cpr.AsyncIOWriterAdapter;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereInterceptor;
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

/**
 * HTML 5 Server Side Events implementation.
 *
 * @author Jeanfrancois Arcand
 */
public class SSEAtmosphereInterceptor implements AtmosphereInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(SSEAtmosphereInterceptor.class);

    private static final byte[] padding;

    static {
        StringBuffer whitespace = new StringBuffer();
        for (int i = 0; i < 2000; i++) {
            whitespace.append(" ");
        }
        whitespace.append("\n");
        padding = whitespace.toString().getBytes();
    }

    @Override
    public void configure(AtmosphereConfig config) {
    }

    private void writePadding(AtmosphereResponse response) {
        if (response.request() != null && response.request().getAttribute("paddingWritten") != null) return;

        response.setContentType("text/event-stream");
        response.setCharacterEncoding("utf-8");
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
    }

    @Override
    public Action inspect(final AtmosphereResource r) {
        final AtmosphereResponse response = r.getResponse();

        r.getRequest().setAttribute(PROPERTY_USE_STREAM, true);
        if (r.transport().equals(AtmosphereResource.TRANSPORT.SSE)) {
            r.addEventListener(new AtmosphereResourceEventListenerAdapter() {
                @Override
                public void onSuspend(AtmosphereResourceEvent event) {
                    writePadding(response);
                }
            });

            response.asyncIOWriter(new AsyncIOWriterAdapter() {
                @Override
                public AsyncIOWriter redirect(String location) throws IOException {
                    response.sendRedirect(location);
                    return this;
                }

                @Override
                public AsyncIOWriter writeError(int errorCode, String message) throws IOException {
                    if (errorCode == 406) {
                        logger.warn("Status code 406: Make sure you aren't setting any @Produces " +
                                "value if you are using Jersey and instead set the @Suspend(content-type=\"...\" value");
                    }
                    response.sendError(errorCode);
                    return this;
                }

                @Override
                public AsyncIOWriter write(String data) throws IOException {
                    padding();
                    response.write("data:" + data + "\n\n");
                    return this;
                }

                // TODO: Performance: execute a single write
                @Override
                public AsyncIOWriter write(byte[] data) throws IOException {
                    padding();
                    response.write("data:").write(data).write("\n\n");
                    return this;
                }

                @Override
                public AsyncIOWriter write(byte[] data, int offset, int length) throws IOException {
                    padding();
                    response.write("data:").write(data, offset, length).write("\n\n");
                    return this;
                }

                private void padding() {
                    if (!r.isSuspended()) {
                        writePadding(response);
                        r.getRequest().setAttribute("paddingWritten", "true");
                    }
                }

                @Override
                public void close() throws IOException {
                    response.closeStreamOrWriter();
                }

                @Override
                public AsyncIOWriter flush() throws IOException {
                    response.flushBuffer();
                    return this;
                }
            });
        }
        return Action.CONTINUE;
    }

    @Override
    public String toString() {
        return "SSE Interceptor Support";
    }
}
