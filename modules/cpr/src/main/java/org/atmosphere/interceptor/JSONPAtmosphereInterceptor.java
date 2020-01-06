/*
 * Copyright 2008-2020 Async-IO.org
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
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereInterceptorAdapter;
import org.atmosphere.cpr.AtmosphereInterceptorWriter;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.HeaderConfig;
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
    private String startChunk = "({\"message\" : \"";
    private AtmosphereConfig config;
    private final static String CONTENT_TYPE = "application/javascript; charset=utf-8";
    private final static String PATTERN = "[^A-Za-z0-9]";

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

        final boolean contain = uri.contains("jsonp");
        if (r.transport().equals(AtmosphereResource.TRANSPORT.JSONP) || contain) {
            super.inspect(r);

            if (contain) {
                startChunk = "(\"";
                endChunk = "\");\r\n\r\n";
            }

            response.setContentType(CONTENT_TYPE);
            AsyncIOWriter writer = response.getAsyncIOWriter();
            if (AtmosphereInterceptorWriter.class.isAssignableFrom(writer.getClass())) {
                AtmosphereInterceptorWriter.class.cast(writer).interceptor(new AsyncIOInterceptorAdapter() {

                    String callbackName() {
                        String callback =  escapeForJavaScript(request.getParameter(HeaderConfig.JSONP_CALLBACK_NAME))
                                .replaceAll(PATTERN, "");
                        if (!callback.isEmpty()) {
                            // Look for extension
                            String jsonp = escapeForJavaScript((String) config.properties().get(HeaderConfig.JSONP_CALLBACK_NAME))
                                    .replaceAll(PATTERN, "");
                            if (!jsonp.isEmpty()) {
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

    private String escapeForJavaScript(String str) {
        try {
            str = StringEscapeUtils.escapeJavaScript(str);
        } catch (Exception e) {
            logger.error("Failed to escape", e);
            str = "";
        }
        return str;
    }

    @Override
    public String toString() {
        return "JSONP Interceptor Support";
    }
}

