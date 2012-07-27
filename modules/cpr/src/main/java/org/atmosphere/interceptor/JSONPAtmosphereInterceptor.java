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
import org.atmosphere.cpr.AsyncIOInterceptor;
import org.atmosphere.cpr.AsyncIOWriter;
import org.atmosphere.cpr.AsyncIOWriterAdapter;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereInterceptorAdapter;
import org.atmosphere.cpr.AtmosphereInterceptorWriter;
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
public class JSONPAtmosphereInterceptor extends AtmosphereInterceptorAdapter {

    @Override
    public Action inspect(AtmosphereResource r) {

        final AtmosphereRequest request = r.getRequest();
        final AtmosphereResponse response = r.getResponse();
        if (r.transport().equals(AtmosphereResource.TRANSPORT.JSONP)) {
            super.inspect(r);

            AsyncIOWriter writer = response.getAsyncIOWriter();
            if (AtmosphereInterceptorWriter.class.isAssignableFrom(writer.getClass())) {
                AtmosphereInterceptorWriter.class.cast(writer).interceptor(new AsyncIOInterceptor() {

                    String contentType() {
                        String c = response.getContentType();
                        if (c == null) {
                            c = (String) request.getAttribute(FrameworkConfig.EXPECTED_CONTENT_TYPE);
                        }

                        if (c  == null) {
                            c = request.getContentType();
                        }

                        return c;
                    }

                    String callbackName() {
                        return request.getParameter(HeaderConfig.JSONP_CALLBACK_NAME);
                    }

                    @Override
                    public void intercept(AtmosphereResponse response, String data) {
                        String contentType = contentType();
                        String callbackName = callbackName();
                        if (!data.startsWith("\"") && !contentType.contains("json")) {
                            data = callbackName + "({\"message\" : \"" + data + "\"});";
                        } else {
                            data = callbackName + "({\"message\" :" + data + "});";
                        }

                        response.write(data);
                    }

                    @Override
                    public void intercept(AtmosphereResponse response, byte[] data) {
                        String contentType = contentType();
                        String callbackName = callbackName();

                        if (contentType != null && !contentType.contains("json")) {
                            response.write(callbackName + "({\"message\" : \"").write(data).write("\"});");
                        } else {
                            response.write(callbackName + "({\"message\" :").write(data).write("});");
                        }
                    }

                    @Override
                    public void intercept(AtmosphereResponse response, byte[] data, int offset, int length) {
                        String contentType = contentType();
                        String callbackName = callbackName();

                        if (contentType != null && !contentType.contains("json")) {
                            response.write(callbackName + "({\"message\" : \"").write(data, offset, length).write("\"});");
                        } else {
                            response.write(callbackName + "({\"message\" :").write(data, offset, length).write("});");
                        }
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
        return "JSONP Interceptor Support";
    }
}

