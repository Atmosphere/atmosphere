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
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.HeaderConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Arrays;

/**
 * JSONP Transport Support.
 *
 * @author Jeanfrancois Arcand
 */
public class JSONPAtmosphereInterceptor extends AtmosphereInterceptorAdapter {

    private static final Logger logger = LoggerFactory.getLogger(JSONPAtmosphereInterceptor.class);
    private static final String END_CHUNK = "\"});";
    private static final Object START_CHUNK = "({\"message\" : \"";

    @Override
    public Action inspect(AtmosphereResource r) {
        final AtmosphereRequest request = r.getRequest();
        final AtmosphereResponse response = r.getResponse();
        if (r.transport().equals(AtmosphereResource.TRANSPORT.JSONP)) {
            super.inspect(r);

            AsyncIOWriter writer = response.getAsyncIOWriter();
            if (AtmosphereInterceptorWriter.class.isAssignableFrom(writer.getClass())) {
                AtmosphereInterceptorWriter.class.cast(writer).interceptor(new AsyncIOInterceptorAdapter() {

                    String callbackName() {
                        return request.getParameter(HeaderConfig.JSONP_CALLBACK_NAME);
                    }

                    @Override
                    public void prePayload(AtmosphereResponse response, byte[] data, int offset, int length) {
                        String callbackName = callbackName();
                        response.write(callbackName + START_CHUNK);
                    }

                    @Override
                    public byte[] transformPayload(AtmosphereResponse response, byte[] responseDraft, byte[] data) throws IOException {
                        if (Arrays.binarySearch(responseDraft, (byte) 0x22) != -1) {
                            String charEncoding = response.getCharacterEncoding() == null ? "UTF-8" : response.getCharacterEncoding();
                            // TODO: TOTALLY INEFFICIENT. We MUST uses binary replacement instead.
                            String s = new String(responseDraft, charEncoding);
                            return s.replaceAll("(['\"\\/])", "\\\\$1")
                            .replaceAll("\b", "\\\\b").replaceAll("\n", "\\\\n")
                            .replaceAll("\t", "\\\\t").replaceAll("\f", "\\\\f")
                            .replaceAll("\r", "\\\\r").getBytes(charEncoding);
                        }
                        return responseDraft;
                    }

                    @Override
                    public void postPayload(AtmosphereResponse response, byte[] data, int offset, int length) {
                        response.write(END_CHUNK, true);
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
        return "JSONP Interceptor Support";
    }
}

