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
import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AsyncIOWriter;
import org.atmosphere.cpr.AsyncIOWriterAdapter;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereInterceptor;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResource.TRANSPORT;
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
 * Fix for the Android 2.2.x bogus HTTP implementation
 *
 * @author Jeanfrancois Arcand
 */
public class AndroidAtmosphereInterceptor implements AtmosphereInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(AndroidAtmosphereInterceptor.class);

    private static final byte[] padding;
    private static final String paddingText;
    static {
        StringBuffer whitespace = new StringBuffer();
        for (int i = 0; i < 4096; i++) {
            whitespace.append(" ");
        }
        whitespace.append("\n");
        paddingText = whitespace.toString();
        padding = paddingText.getBytes();
    }

    @Override
    public void configure(AtmosphereConfig config) {
    }

    @Override
    public Action inspect(final AtmosphereResource r) {
        final AtmosphereResponse response = r.getResponse();
        String userAgent = r.getRequest().getHeader("User-Agent");

        if (r.transport().equals(TRANSPORT.STREAMING) && userAgent != null && userAgent.indexOf("Android 2.3") != -1) {
            r.padding("whitespace");
            response.asyncIOWriter(new AsyncIOWriterAdapter() {
                @Override
                public AsyncIOWriter redirect(String location) throws IOException {
                    response.sendRedirect(location);
                    return this;
                }

                @Override
                public AsyncIOWriter writeError(int errorCode, String message) throws IOException {
                    response.sendError(errorCode);
                    return this;
                }

                @Override
                public AsyncIOWriter write(String data) throws IOException {
                    response.write(paddingText).write(data);
                    return this;
                }

                @Override
                public AsyncIOWriter write(byte[] data) throws IOException {
                    response.write(padding).write(data);
                    return this;
                }

                @Override
                public AsyncIOWriter write(byte[] data, int offset, int length) throws IOException {
                    response.write(padding).write(data, offset, length);
                    return this;
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
    public void postInspect(AtmosphereResource r) {
    }

    @Override
    public String toString() {
        return "Android Interceptor Support";
    }
}
