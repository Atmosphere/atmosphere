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

import java.io.IOException;

/**
 * Adapter class for {@link AsyncIOWriter}. Implementation of this class must implements one of each method to prevent
 * a StackOverflow.
 */
public abstract class AsyncIOWriterAdapter implements AsyncIOWriter {

    private final AtmosphereResponse r;

    public AsyncIOWriterAdapter(AtmosphereResponse r) {
        this.r = r;
    }

    public AsyncIOWriterAdapter() {
        this.r = new AtmosphereResponse.Builder().build();
    }

    /**
     * No OPS
     */
    @Override
    public AsyncIOWriter redirect(String location) throws IOException {
        return redirect(r, location);
    }

    /**
     * No OPS
     */
    @Override
    public AsyncIOWriter writeError(int errorCode, String message) throws IOException {
        return writeError(r, errorCode, message);
    }

    /**
     * No OPS
     */
    @Override
    public AsyncIOWriter write(String data) throws IOException {
        return write(r, data);
    }

    /**
     * No OPS
     */
    @Override
    public AsyncIOWriter write(byte[] data) throws IOException {
        return write(r, data);
    }

    /**
     * No OPS
     */
    @Override
    public AsyncIOWriter write(byte[] data, int offset, int length) throws IOException {
        return write(r, data, offset, length);
    }

    /**
     * No OPS
     */
    @Override
    public void close() throws IOException {
        close(r);
    }

    /**
     * No OPS
     */
    @Override
    public AsyncIOWriter flush() throws IOException {
        return flush(r);
    }

    /**
     * No OPS
     */
    @Override
    public AsyncIOWriter redirect(AtmosphereResponse r, String location) throws IOException {
        return redirect(location);
    }

    /**
     * No OPS
     */
    @Override
    public AsyncIOWriter writeError(AtmosphereResponse r, int errorCode, String message) throws IOException {
        return writeError(errorCode,message);
    }

    /**
     * No OPS
     */
    @Override
    public AsyncIOWriter write(AtmosphereResponse r, String data) throws IOException {
        return write(data);
    }

    /**
     * No OPS
     */
    @Override
    public AsyncIOWriter write(AtmosphereResponse r, byte[] data) throws IOException {
        write(data);
        return this;
    }

    /**
     * No OPS
     */
    @Override
    public AsyncIOWriter write(AtmosphereResponse r, byte[] data, int offset, int length) throws IOException {
        return write(data,offset,length);
    }

    /**
     * No OPS
     */
    @Override
    public void close(AtmosphereResponse r) throws IOException {
        close();
    }

    /**
     * No OPS
     */
    @Override
    public AsyncIOWriter flush(AtmosphereResponse r) throws IOException {
        return flush();
    }
}
