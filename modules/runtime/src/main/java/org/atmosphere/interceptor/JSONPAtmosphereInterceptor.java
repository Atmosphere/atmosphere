/*
 * Copyright 2017 Async-IO.org
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

import org.atmosphere.runtime.Action;
import org.atmosphere.runtime.AsyncIOInterceptorAdapter;
import org.atmosphere.runtime.AsyncIOWriter;
import org.atmosphere.runtime.AtmosphereConfig;
import org.atmosphere.runtime.AtmosphereInterceptorAdapter;
import org.atmosphere.runtime.AtmosphereInterceptorWriter;
import org.atmosphere.runtime.AtmosphereRequest;
import org.atmosphere.runtime.AtmosphereResource;
import org.atmosphere.runtime.AtmosphereResponse;
import org.atmosphere.runtime.HeaderConfig;
import org.atmosphere.util.StringEscapeUtils;
import org.atmosphere.util.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * JSONP Transport Support.
 *
 * @author Jeanfrancois Arcand
 */
public class JSONPAtmosphereInterceptor extends AtmosphereInterceptorAdapter {

    private static final Logger logger = LoggerFactory.getLogger(JSONPAtmosphereInterceptor.class);
    private String endChunk = "\"});";
    private String startChunk = "({\"message\":\"";
    private AtmosphereConfig config;

    @Override
    public void configure(AtmosphereConfig config) {
        this.config = config;
    }

    @Override
    public Action inspect(AtmosphereResource r) {

        if (Utils.webSocketMessage(r)) return Action.CONTINUE;

        final AtmosphereRequest request = r.getRequest();
        final AtmosphereResponse response = r.getResponse();
        // Shield from Broken server
        String uri = request.getRequestURI() == null ? "" : request.getRequestURI();

        if (r.transport().equals(AtmosphereResource.TRANSPORT.JSONP) || uri.indexOf("jsonp") != -1) {
            super.inspect(r);

            if (uri.indexOf("jsonp") != -1) {
                startChunk = "(\"";
                endChunk = "\");\r\n\r\n";
            }

            AsyncIOWriter writer = response.getAsyncIOWriter();
            if (AtmosphereInterceptorWriter.class.isAssignableFrom(writer.getClass())) {
                AtmosphereInterceptorWriter.class.cast(writer).interceptor(new AsyncIOInterceptorAdapter() {

                    String callbackName() {
                        String callback =  request.getParameter(HeaderConfig.JSONP_CALLBACK_NAME);
                        if (callback == null) {
                            // Look for extension
                            String jsonp = (String) config.properties().get(HeaderConfig.JSONP_CALLBACK_NAME);
                            if (jsonp != null) {
                                callback = request.getParameter(jsonp);
                            }
                        }
                        return callback;
                    }

                    @Override
                    public void prePayload(AtmosphereResponse response, byte[] data, int offset, int length) {
                        String callbackName = callbackName();
                        response.write(callbackName + startChunk);
                    }

                    @Override
                    public byte[] transformPayload(AtmosphereResponse response, byte[] responseDraft, byte[] data) throws IOException {
                        String charEncoding = response.getCharacterEncoding() == null ? "UTF-8" : response.getCharacterEncoding();
                        return escapeForJavaScript(new String(responseDraft, charEncoding)).getBytes(charEncoding);
                    }

                    @Override
                    public void postPayload(AtmosphereResponse response, byte[] data, int offset, int length) {
                        response.write(endChunk, true);
                    }
                });
            } else {
                logger.warn("Unable to apply {}. Your AsyncIOWriter must implement {}", getClass().getName(), AtmosphereInterceptorWriter.class.getName());
            }
        }
        return Action.CONTINUE;
    }

    protected String escapeForJavaScript(String str) {
        try {
            str = StringEscapeUtils.escapeJavaScript(str);
        } catch (Exception e) {
            logger.error("Failed to escape", e);
            str = null;
        }
        return str;
    }

    @Override
    public String toString() {
        return "JSONP Interceptor Support";
    }
}

