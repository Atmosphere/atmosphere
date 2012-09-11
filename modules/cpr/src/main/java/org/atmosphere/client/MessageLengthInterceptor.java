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
package org.atmosphere.client;

import org.atmosphere.cpr.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * An {@link AtmosphereInterceptor} that add a special String "|" at the end of a message, allowing the
 * atmosphere.js to detect if one or several messages where aggregated in one write operations.
 * <p/>
 * The special String is configurable using {@link ApplicationConfig#MESSAGE_DELIMITER}
 *
 * @author Jeanfrancois Arcand
 * @deprecated - Use the {@link TrackMessageSizeInterceptor}
 */
public class MessageLengthInterceptor extends AtmosphereInterceptorAdapter {

    private static final Logger logger = LoggerFactory.getLogger(MessageLengthInterceptor.class);

    private final static byte[] END = "|".getBytes();
    private byte[] end = END;
    private String endString = "|";

    @Override
    public void configure(AtmosphereConfig config) {
        String s = config.getInitParameter(ApplicationConfig.MESSAGE_DELIMITER);
        if (s != null) {
            end = s.getBytes();
            endString = s;
        }
    }

    @Override
    public Action inspect(final AtmosphereResource r) {
        final AtmosphereResponse response = r.getResponse();

        super.inspect(r);

        AsyncIOWriter writer = response.getAsyncIOWriter();
        if (AtmosphereInterceptorWriter.class.isAssignableFrom(writer.getClass())) {
            AtmosphereInterceptorWriter.class.cast(writer).interceptor(new AsyncIOInterceptor() {

                @Override
                public void prePayload(AtmosphereResponse response, byte[] data, int offset, int length) {
                }

                @Override
                public byte[] transformPayload(AtmosphereResponse response, byte[] responseDraft, byte[] data) throws IOException {
                    return responseDraft;
                }

                @Override
                public void postPayload(AtmosphereResponse response, byte[] data, int offset, int length) {
                    response.write(end);
                }
            });
        } else {
            logger.warn("Unable to apply {}. Your AsyncIOWriter must implement {}", getClass().getName(), AtmosphereInterceptorWriter.class.getName());
        }
        return Action.CONTINUE;
    }

    @Override
    public String toString() {
        return endString + " End Message Interceptor";
    }
}
