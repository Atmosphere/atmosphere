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
import org.atmosphere.runtime.AtmosphereInterceptorAdapter;
import org.atmosphere.runtime.AtmosphereInterceptorWriter;
import org.atmosphere.runtime.AtmosphereResource;
import org.atmosphere.runtime.AtmosphereResource.TRANSPORT;
import org.atmosphere.runtime.AtmosphereResourceImpl;
import org.atmosphere.runtime.AtmosphereResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fix for the Android 2.2.x bogus HTTP implementation.
 *
 * @author Jeanfrancois Arcand
 */
public class AndroidAtmosphereInterceptor extends AtmosphereInterceptorAdapter {

    private static final Logger logger = LoggerFactory.getLogger(AndroidAtmosphereInterceptor.class);

    private static final byte[] padding;
    private static final String paddingText;

    static {
        StringBuilder whitespace = new StringBuilder();
        for (int i = 0; i < 4096; i++) {
            whitespace.append(" ");
        }
        whitespace.append("\n");
        paddingText = whitespace.toString();
        padding = paddingText.getBytes();
    }

    @Override
    public Action inspect(final AtmosphereResource r) {

        if (!r.transport().equals(TRANSPORT.STREAMING)) return Action.CONTINUE;

        final AtmosphereResponse response = AtmosphereResourceImpl.class.cast(r).getResponse(false);
        String userAgent = AtmosphereResourceImpl.class.cast(r).getRequest(false).getHeader("User-Agent");

        if (userAgent != null &&
                (userAgent.indexOf("Android 2.") != -1 || userAgent.indexOf("Android 3.") != -1)) {
            super.inspect(r);

            AsyncIOWriter writer = response.getAsyncIOWriter();
            if (AtmosphereInterceptorWriter.class.isAssignableFrom(writer.getClass())) {
                AtmosphereInterceptorWriter.class.cast(writer).interceptor(new AsyncIOInterceptorAdapter() {

                    @Override
                    public void prePayload(AtmosphereResponse response, byte[] data, int offset, int length) {
                        response.write(padding, true);
                    }

                    @Override
                    public void postPayload(AtmosphereResponse response, byte[] data, int offset, int length) {
                        response.write(padding, true);
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
        return "Android Interceptor Support";
    }
}
