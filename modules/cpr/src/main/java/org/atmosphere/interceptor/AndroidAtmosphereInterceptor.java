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
import org.atmosphere.cpr.AtmosphereResource.TRANSPORT;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Fix for the Android 2.2.x bogus HTTP implementation
 *
 * @author Jeanfrancois Arcand
 */
public class AndroidAtmosphereInterceptor extends AtmosphereInterceptorAdapter {

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
    public Action inspect(final AtmosphereResource r) {

        final AtmosphereResponse response = r.getResponse();
        String userAgent = r.getRequest().getHeader("User-Agent");

        if (r.transport().equals(TRANSPORT.STREAMING) && userAgent != null &&
                (userAgent.indexOf("Android 2.") != -1 || userAgent.indexOf("Android 3.") != -1)) {
            super.inspect(r);
            r.padding("whitespace");

            AsyncIOWriter writer = response.getAsyncIOWriter();
            if (AtmosphereInterceptorWriter.class.isAssignableFrom(writer.getClass())) {
                AtmosphereInterceptorWriter.class.cast(writer).interceptor(new AsyncIOInterceptor() {
                    @Override
                    public void prePayload(AtmosphereResponse response, String data) {
                        response.write(paddingText);
                    }

                    @Override
                    public void prePayload(AtmosphereResponse response, byte[] data) {
                        response.write(padding);
                    }

                    @Override
                    public void prePayload(AtmosphereResponse response, byte[] data, int offset, int length) {
                        response.write(padding);
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
                    }

                    @Override
                    public void postPayload(AtmosphereResponse response, byte[] data) {
                    }

                    @Override
                    public void postPayload(AtmosphereResponse response, byte[] data, int offset, int length) {
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
        return "Android Interceptor Support";
    }
}
