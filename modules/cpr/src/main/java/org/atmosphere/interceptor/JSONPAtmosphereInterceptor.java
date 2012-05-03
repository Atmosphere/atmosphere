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

import org.atmosphere.cpr.AsyncIOWriter;
import org.atmosphere.cpr.AtmosphereFramework;
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
    public AtmosphereFramework.Action inspect(AtmosphereResource r) {

        final AtmosphereRequest request = r.getRequest();
        final AtmosphereResponse response = r.getResponse();
        if (r.transport().equals(AtmosphereResource.TRANSPORT.JSONP)) {
            response.asyncIOWriter(new AsyncIOWriter() {

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
                public void redirect(String location) throws IOException {
                    response.sendRedirect(location);
                }

                @Override
                public void writeError(int errorCode, String message) throws IOException {
                    response.sendError(errorCode);
                }

                @Override
                public void write(String data) throws IOException {
                    String contentType = contentType();
                    String callbackName = callbackName();
                    if (contentType != null && !contentType.contains("json")) {
                        data = callbackName + "({\"message\" : \"" + data + "\"})";
                    } else {
                        data = callbackName + "({\"message\" :" + data + "})";
                    }

                    response.write(data);
                }

                @Override
                public void write(byte[] data) throws IOException {
                    String contentType = contentType();
                    String callbackName = callbackName();

                    if (contentType != null && !contentType.contains("json")) {
                        response.write(callbackName + "({\"message\" : \"").write(data).write("\"})");
                    } else {
                        response.write(callbackName + "({\"message\" :").write(data).write("})");
                    }
                }

                @Override
                public void write(byte[] data, int offset, int length) throws IOException {
                    String contentType = contentType();
                    String callbackName = callbackName();

                    if (contentType != null && !contentType.contains("json")) {
                        response.write(callbackName + "({\"message\" : \"").write(data, offset, length).write("\"})");
                    } else {
                        response.write(callbackName + "({\"message\" :").write(data, offset, length).write("})");
                    }
                }

                @Override
                public void close() throws IOException {
                    response.closeStreamOrWriter();
                }

                @Override
                public void flush() throws IOException {
                    response.flushBuffer();
                }
            });
        }
        return new AtmosphereFramework.Action(AtmosphereFramework.Action.TYPE.CONTINUE);
    }

    @Override
    public String toString() {
        return "JSONP-Support";
    }
}

