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
package org.atmosphere.util;

import org.atmosphere.runtime.AsyncIOWriter;
import org.atmosphere.runtime.AsyncIOWriterAdapter;
import org.atmosphere.runtime.AtmosphereResponse;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * An {@link AsyncIOWriter} backed by an {@link ByteArrayOutputStream}
 *
 * @author Jeanfrancois Arcand
 */
public class ByteArrayAsyncWriter extends AsyncIOWriterAdapter {

    ByteArrayOutputStream o = new ByteArrayOutputStream();

    @Override
    public AsyncIOWriter write(AtmosphereResponse r, String data) throws IOException {
        o.write(data.getBytes(r.getCharacterEncoding()));
        return this;
    }

    @Override
    public AsyncIOWriter write(AtmosphereResponse r, byte[] data) throws IOException {
        o.write(data);
        return this;
    }

    @Override
    public AsyncIOWriter write(AtmosphereResponse r, byte[] data, int offset, int length) throws IOException {
        o.write(data, offset, length);
        return this;
    }

    public ByteArrayOutputStream stream() {
        return o;
    }

    @Override
    public void close(AtmosphereResponse r) {
        o.reset();
    }
}
