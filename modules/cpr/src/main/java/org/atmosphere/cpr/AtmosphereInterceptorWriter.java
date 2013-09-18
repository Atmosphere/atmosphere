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
package org.atmosphere.cpr;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedList;

/**
 * An {@link AsyncIOWriter} that delegates the write operation to its {@link AsyncIOInterceptor}. If no
 * AsyncIOInterceptor is specified, this class does nothing and the responses will never get written.
 *
 * @author Jeanfrancois Arcand
 */
public class AtmosphereInterceptorWriter extends AsyncIOWriterAdapter {

    protected final LinkedList<AsyncIOInterceptor> filters = new LinkedList<AsyncIOInterceptor>();

    public AtmosphereInterceptorWriter() {
    }

    @Override
    public AsyncIOWriter redirect(AtmosphereResponse response, String location) throws IOException {
        for (AsyncIOInterceptor i : filters) {
            i.redirect(response, location);
        }
        return this;
    }

    @Override
    public AsyncIOWriter writeError(AtmosphereResponse response, int errorCode, String message) throws IOException {
        for (AsyncIOInterceptor i : filters) {
            byte[] b = i.error(response, errorCode, message);
            writeReady(response, b);
        }
        return this;
    }

    @Override
    public AsyncIOWriter write(AtmosphereResponse response, String data) throws IOException {
        return write(response, data.getBytes(response.getCharacterEncoding()));
    }

    @Override
    public AsyncIOWriter write(AtmosphereResponse response, byte[] data) throws IOException {
        return write(response, data, 0, data.length);
    }

    @Override
    public AsyncIOWriter write(AtmosphereResponse response, byte[] data, int offset, int length) throws IOException {
        invokeInterceptor(response, data, offset, length);
        return this;
    }

    protected void invokeInterceptor(AtmosphereResponse response, byte[] data, int offset, int length) throws IOException {
        for (AsyncIOInterceptor i : filters) {
            i.prePayload(response, data, offset, length);
        }

        byte[] responseDraft = new byte[length];
        System.arraycopy(data, offset, responseDraft, 0, length);
        for (AsyncIOInterceptor i : filters) {
            responseDraft = i.transformPayload(response, responseDraft, data);
        }
        writeReady(response, responseDraft);

        LinkedList<AsyncIOInterceptor> reversedFilters = (LinkedList<AsyncIOInterceptor>) filters.clone();
        Collections.reverse(reversedFilters);
        for (AsyncIOInterceptor i : reversedFilters) {
            i.postPayload(response, data, offset, length);
        }

    }

    protected void writeReady(AtmosphereResponse response, byte[] responseDraft) throws IOException {
        response.write(responseDraft);
    }

    @Override
    public void close(AtmosphereResponse response) throws IOException {
        response.closeStreamOrWriter();
    }

    @Override
    public AsyncIOWriter flush(AtmosphereResponse response) throws IOException {
        response.flushBuffer();
        return this;
    }

    /**
     * Add an {@link AsyncIOInterceptor} that will be invoked in the order it was added.
     *
     * @param filter {@link AsyncIOInterceptor
     * @return this
     */
    public AtmosphereInterceptorWriter interceptor(AsyncIOInterceptor filter) {
        if (!filters.contains(filter)) {
            filters.addFirst(filter);
        }
        return this;
    }
}
