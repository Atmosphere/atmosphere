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
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereInterceptorAdapter;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResource.TRANSPORT;
import org.atmosphere.cpr.AtmosphereResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * atmosphere.js's onOpen callback interceptor to make long-polling and onOpen's callback reliable.
 *
 * @author Jeanfrancois Arcand
 */
public class LongPollingOnOpenInterceptor extends AtmosphereInterceptorAdapter {

    private static final Logger logger = LoggerFactory.getLogger(LongPollingOnOpenInterceptor.class);

    private static final byte[] padding;
    private static final String paddingText;

    static {
        StringBuilder whitespace = new StringBuilder();
        for (int i = 0; i < 8192; i++) {
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

        if (r.transport().equals(TRANSPORT.LONG_POLLING) || r.transport().equals(TRANSPORT.JSONP)) {
            response.write(padding, true);
            try {
                response.flushBuffer();
            } catch (IOException e) {
                logger.trace("", e);
            }
        }
        return Action.CONTINUE;
    }

    @Override
    public String toString() {
        return "Long-Polling Padding Interceptor Support";
    }

}
