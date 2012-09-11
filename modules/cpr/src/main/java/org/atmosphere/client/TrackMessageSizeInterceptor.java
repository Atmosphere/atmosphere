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

import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AsyncIOInterceptor;
import org.atmosphere.cpr.AsyncIOWriter;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereInterceptorAdapter;
import org.atmosphere.cpr.AtmosphereInterceptorWriter;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;

/**
 * An {@link org.atmosphere.cpr.AtmosphereInterceptor} that add a add message size and delimiter.
 * <p/>
 * The special String is configurable using {@link org.atmosphere.cpr.ApplicationConfig#MESSAGE_DELIMITER}
 *
 * @author Jeanfrancois Arcand
 */
public class TrackMessageSizeInterceptor extends AtmosphereInterceptorAdapter {

    private static final Logger logger = LoggerFactory.getLogger(TrackMessageSizeInterceptor.class);
    private final static byte[] END = "|".getBytes();
    private final static String IN_ENCODING = "UTF-8";
    private final static String OUT_ENCODING = "UTF-8";

    private byte[] end = END;
    private String endString = "|";
    private final Charset inCharset = Charset.forName(IN_ENCODING);
    private final Charset outCharset = Charset.forName(OUT_ENCODING);

    private final Interceptor interceptor = new Interceptor();

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
            AtmosphereInterceptorWriter.class.cast(writer).interceptor(interceptor);
        } else {
            logger.warn("Unable to apply {}. Your AsyncIOWriter must implement {}", getClass().getName(), AtmosphereInterceptorWriter.class.getName());
        }
        return Action.CONTINUE;
    }


    @Override
    public String toString() {
        return " Track Message Size Interceptor using " + endString;
    }

    private final class Interceptor implements AsyncIOInterceptor {

        @Override
        public void prePayload(AtmosphereResponse response, byte[] data, int offset, int length) {
        }

        @Override
        public byte[] transformPayload(AtmosphereResponse response, byte[] responseDraft, byte[] data) throws IOException {
            response.setCharacterEncoding(OUT_ENCODING);
            return transform(responseDraft);
        }

        @Override
        public void postPayload(AtmosphereResponse response, byte[] data, int offset, int length) {
        }

        private byte[] transform(byte[] input) throws UnsupportedEncodingException, CharacterCodingException {
             return transform(input, 0, input.length);
         }

         private byte[] transform(byte[] input, int offset, int length) throws CharacterCodingException, UnsupportedEncodingException {
             CharBuffer cb = inCharset.newDecoder().decode(ByteBuffer.wrap(input, offset, length));
             int size = cb.length();
             CharBuffer cb2 = CharBuffer.wrap(Integer.toString(size) + endString);
             ByteBuffer bb = ByteBuffer.allocate((cb2.length() + size) * 2);
             CharsetEncoder encoder = outCharset.newEncoder();
             encoder.encode(cb2, bb, false);
             encoder.encode(cb, bb, false);
             bb.flip();
             byte[] b = new byte[bb.limit()];
             bb.get(b);
             return b;
         }
    }
}
