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
package org.atmosphere.cpr;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;

/**
 * An {@link AsyncIOWriter} that delegates the write operation to it's {@link AsyncIOInterceptor}. If no
 * AsyncIOInterceptor are specified, this class does nothing and the response's will never get written.
 *
 * @author Jeanfrancois Arcand
 */
public class AtmosphereInterceptorWriter extends AsyncIOWriterAdapter {

    private static final Logger logger = LoggerFactory.getLogger(AtmosphereInterceptorWriter.class);
    private final AtmosphereResponse response;

    private final ArrayList<AsyncIOInterceptor> filters = new ArrayList<AsyncIOInterceptor>();

    public AtmosphereInterceptorWriter(AtmosphereResponse response) {
        this.response = response;
    }

    @Override
    public AsyncIOWriter redirect(String location) throws IOException {
        response.sendRedirect(location);
        return this;
    }

    @Override
    public AsyncIOWriter writeError(int errorCode, String message) throws IOException {
        response.sendError(errorCode);
        return this;
    }

    @Override
    public AsyncIOWriter write(String data) throws IOException {
        return write(data.getBytes());
    }

    @Override
    public AsyncIOWriter write(byte[] data) throws IOException {
        for (AsyncIOInterceptor i : filters) {
            i.prePayload(response, data);
        }

        byte[] transformedData = new byte[16];
        for (AsyncIOInterceptor i : filters) {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream(transformedData.length);
            outputStream.write(transformedData);
            i.transformPayload(outputStream, data);

            transformedData = outputStream.toByteArray();
        }
        response.write(transformedData);

        ArrayList<AsyncIOInterceptor> reversedFilters = filters;
        Collections.reverse(reversedFilters);
        for (AsyncIOInterceptor i : reversedFilters) {
            i.postPayload(response, data);
        }

        return this;
    }

    @Override
    public AsyncIOWriter write(byte[] data, int offset, int length) throws IOException {
        // @todo: make this significant or remove
        return write(data);
    }

    @Override
    public void close() throws IOException {
        response.closeStreamOrWriter();
    }

    @Override
    public AsyncIOWriter flush() throws IOException {
        response.flushBuffer();
        return this;
    }

    public AtmosphereInterceptorWriter interceptor(AsyncIOInterceptor filter) {
        filters.add(filter);
        return this;
    }
}
