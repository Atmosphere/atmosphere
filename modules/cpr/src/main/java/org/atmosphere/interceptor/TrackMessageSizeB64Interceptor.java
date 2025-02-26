/*
 * Copyright 2008-2025 Async-IO.org
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
import org.atmosphere.util.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;

import static org.atmosphere.cpr.ApplicationConfig.EXCLUDED_CONTENT_TYPES;

/**
 * An {@link org.atmosphere.cpr.AtmosphereInterceptor} that adds message size and delimiter, and encodes the message in Base64.
 * This allows for broadcasting of messages containing the delimiter character.
 * <p/>
 * You can configure this class to exclude some response's content-type by using the {@link ApplicationConfig#EXCLUDED_CONTENT_TYPES}
 *
 * @author Jeanfrancois Arcand
 * @author Martin Mačura
 */
public class TrackMessageSizeB64Interceptor extends AtmosphereInterceptorAdapter {

    private static final Logger logger = LoggerFactory.getLogger(TrackMessageSizeB64Interceptor.class);
    private static final String DELIMITER = "|";
    private final static String OUT_ENCODING = "UTF-8";
    public final static String SKIP_INTERCEPTOR = TrackMessageSizeB64Interceptor.class.getName() + ".skip";

    private final HashSet<String> excludedContentTypes = new HashSet<>();

    private final Interceptor interceptor = new Interceptor();

    private HeartbeatInterceptor heartbeatInterceptor;

    @Override
    public void configure(AtmosphereConfig config) {
        String s = config.getInitParameter(EXCLUDED_CONTENT_TYPES);
        if (s != null) {
            excludedContentTypes.addAll(Arrays.asList(s.split(",")));
        }
        heartbeatInterceptor = config.framework().interceptor(HeartbeatInterceptor.class);
    }

    /**
     * Excluse response's content-type from being processed by this class.
     *
     * @param excludedContentType the value of {@link AtmosphereResponseImpl#getContentType()}
     * @return this
     */
    public TrackMessageSizeB64Interceptor excludedContentType(String excludedContentType) {
        excludedContentTypes.add(excludedContentType.toLowerCase());
        return this;
    }

    @Override
    public Action inspect(final AtmosphereResource r) {

        if (Utils.webSocketMessage(r)) return Action.CONTINUE;

        final AtmosphereResponse response = r.getResponse();

        super.inspect(r);

        AsyncIOWriter writer = response.getAsyncIOWriter();
        if (AtmosphereInterceptorWriter.class.isAssignableFrom(writer.getClass())) {
            ((AtmosphereInterceptorWriter) writer).interceptor(interceptor);
        } else {
            logger.warn("Unable to apply {}. Your AsyncIOWriter must implement {}", getClass().getName(), AtmosphereInterceptorWriter.class.getName());
        }
        return Action.CONTINUE;
    }

    @Override
    public String toString() {
        return " Track Message Size Base64 Interceptor using " + DELIMITER;
    }

    private final class Interceptor extends AsyncIOInterceptorAdapter {
        @Override
        public byte[] transformPayload(AtmosphereResponse response, byte[] responseDraft, byte[] data) throws IOException {

            if (response.request().getAttribute(SKIP_INTERCEPTOR) == null
                    && Boolean.parseBoolean(response.request().getHeader(HeaderConfig.X_ATMOSPHERE_TRACKMESSAGESIZE))
                    && (response.getContentType() == null
                    || !excludedContentTypes.contains(response.getContentType().toLowerCase()))) {
                response.setCharacterEncoding(OUT_ENCODING);
                String s = Base64.getEncoder().encodeToString(responseDraft);

                if (isMessageAlreadyEncoded(new String(data))) {
                    logger.trace("Message already encoded {}", s);
                    return responseDraft;
                }

                return (s.length() + DELIMITER + s).getBytes(OUT_ENCODING);
            } else {
                return responseDraft;
            }
        }
    }

    public boolean isMessageAlreadyEncoded(String message) {

        if (heartbeatInterceptor != null &&
                message.endsWith(DELIMITER + new String(heartbeatInterceptor.getPaddingBytes()))) {
            return true;
        }
        return false;
    }
}
