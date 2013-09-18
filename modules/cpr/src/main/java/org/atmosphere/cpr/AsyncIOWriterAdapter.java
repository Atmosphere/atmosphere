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

/**
 * Adapter class for {@link AsyncIOWriter}.
 *
 * @author Jeanfrancois Arcand
 */
public class AsyncIOWriterAdapter implements AsyncIOWriter {

    public AsyncIOWriterAdapter() {
    }

    @Override
    public AsyncIOWriter redirect(AtmosphereResponse r, String location) throws IOException {
        return this;
    }

    @Override
    public AsyncIOWriter writeError(AtmosphereResponse r, int errorCode, String message) throws IOException {
        return this;
    }

    @Override
    public AsyncIOWriter write(AtmosphereResponse r, String data) throws IOException {
        return this;
    }

    @Override
    public AsyncIOWriter write(AtmosphereResponse r, byte[] data) throws IOException {
        return this;
    }

    @Override
    public AsyncIOWriter write(AtmosphereResponse r, byte[] data, int offset, int length) throws IOException {
        return this;
    }

    @Override
    public void close(AtmosphereResponse r) throws IOException {
    }

    @Override
    public AsyncIOWriter flush(AtmosphereResponse r) throws IOException {
        return this;
    }
}
