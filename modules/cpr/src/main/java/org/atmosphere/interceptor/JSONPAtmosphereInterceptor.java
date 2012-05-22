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
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereInterceptor;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.FrameworkConfig;
import org.atmosphere.cpr.HeaderConfig;

import java.io.IOException;

/**
 * JSONP Transport Support.
 *
 * @author Jeanfrancois Arcand
 */
public class JSONPAtmosphereInterceptor implements AtmosphereInterceptor {

    @Override
    public Action inspect(AtmosphereResource r) {

        final AtmosphereRequest request = r.getRequest();
        final AtmosphereResponse response = r.getResponse();
        if (r.transport().equals(AtmosphereResource.TRANSPORT.JSONP)) {
            response.asyncIOWriter(new AsyncIOWriterAdapter() {

                String contentType() {
                    String c = response.getContentType();
                    if (c == null) {
                        c = (String) request.getAttribute(FrameworkConfig.EXPECTED_CONTENT_TYPE);
                    }
                    return c;
                }

                String callbackName() {
                    return request.getParameter(HeaderConfig.JSONP_CALLBACK_NAME);
                }

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
                    String callbackName = callbackName();
                    if (!data.startsWith("\"")) {
                        data = callbackName + "({\"message\" : \"" + data + "\"})";
                    } else {
                        data = callbackName + "({\"message\" :" + data + "})";
                    }

                    response.write(data);
                    return this;
                }

                @Override
                public AsyncIOWriter write(byte[] data) throws IOException {
                    String contentType = contentType();
                    String callbackName = callbackName();

                    if (contentType != null && !contentType.contains("json")) {
                        response.write(callbackName + "({\"message\" : \"").write(data).write("\"})");
                    } else {
                        response.write(callbackName + "({\"message\" :").write(data).write("})");
                    }
                    return this;
                }

                @Override
                public AsyncIOWriter write(byte[] data, int offset, int length) throws IOException {
                    String contentType = contentType();
                    String callbackName = callbackName();

                    if (contentType != null && !contentType.contains("json")) {
                        response.write(callbackName + "({\"message\" : \"").write(data, offset, length).write("\"})");
                    } else {
                        response.write(callbackName + "({\"message\" :").write(data, offset, length).write("})");
                    }
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
    public String toString() {
        return "JSONP Interceptor Support";
    }
}

