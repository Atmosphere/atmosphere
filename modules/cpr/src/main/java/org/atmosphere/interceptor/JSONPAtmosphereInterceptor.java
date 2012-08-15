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
                    public void prePayload(AtmosphereResponse response, String data) {
                        String callbackName = callbackName();
                        String contentType = contentType();

                        response.write(callbackName + "({\"message\" : ");
                        if (!data.startsWith("\"") && !contentType.contains("json")) {
                            response.write("\"");
                        }
                    }

                    @Override
                    public void prePayload(AtmosphereResponse response, byte[] data) {
                        String callbackName = callbackName();
                        String contentType = contentType();

                        response.write(callbackName + "({\"message\" : ");
                        if (contentType != null && !contentType.contains("json")) {
                            response.write("\"");
                        }
                    }

                    @Override
                    public void prePayload(AtmosphereResponse response, byte[] data, int offset, int length) {
                        String callbackName = callbackName();
                        String contentType = contentType();

                        response.write(callbackName + "({\"message\" : ");
                        if (contentType != null && !contentType.contains("json")) {
                            response.write("\"");
                        }
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
                    public byte[] transformPayload(byte[] responseDraft, byte[] data, int offset, int length) throws IOException {
                        return responseDraft;
                    }

                    @Override
                    public void postPayload(AtmosphereResponse response, String data) {
                        String contentType = contentType();

                        if (contentType != null && !contentType.contains("json")) {
                            response.write("\"");
                        }

                        response.write("});");
                    }

                    @Override
                    public void postPayload(AtmosphereResponse response, byte[] data) {
                        String contentType = contentType();

                        if (contentType != null && !contentType.contains("json")) {
                            response.write("\"");
                        }

                        response.write("});");
                    }

                    @Override
                    public void postPayload(AtmosphereResponse response, byte[] data, int offset, int length) {
                        String contentType = contentType();

                        if (contentType != null && !contentType.contains("json")) {
                            response.write("\"");
                        }

                        response.write("});");
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

