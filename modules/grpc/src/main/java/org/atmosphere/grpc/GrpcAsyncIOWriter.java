/*
 * Copyright 2008-2026 Async-IO.org
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
package org.atmosphere.grpc;

import org.atmosphere.cpr.AsyncIOWriter;
import org.atmosphere.cpr.AtmosphereResponse;

import java.io.IOException;
import java.util.Arrays;

/**
 * Bridges Atmosphere's response writing to a {@link GrpcChannel}.
 * When a Broadcaster writes to an AtmosphereResponse, data flows through to the gRPC stream.
 */
public class GrpcAsyncIOWriter implements AsyncIOWriter {

    private final GrpcChannel channel;

    public GrpcAsyncIOWriter(GrpcChannel channel) {
        this.channel = channel;
    }

    @Override
    public AsyncIOWriter redirect(AtmosphereResponse r, String url) throws IOException {
        return this;
    }

    @Override
    public AsyncIOWriter writeError(AtmosphereResponse r, int errorCode, String message) throws IOException {
        channel.write("ERROR " + errorCode + ": " + message);
        return this;
    }

    @Override
    public AsyncIOWriter write(AtmosphereResponse r, String data) throws IOException {
        channel.write(data);
        return this;
    }

    @Override
    public AsyncIOWriter write(AtmosphereResponse r, byte[] data) throws IOException {
        channel.write(data);
        return this;
    }

    @Override
    public AsyncIOWriter write(AtmosphereResponse r, byte[] data, int offset, int length) throws IOException {
        if (offset == 0 && length == data.length) {
            channel.write(data);
        } else {
            channel.write(Arrays.copyOfRange(data, offset, offset + length));
        }
        return this;
    }

    @Override
    public void close(AtmosphereResponse r) throws IOException {
        channel.close();
    }

    @Override
    public AsyncIOWriter flush(AtmosphereResponse r) throws IOException {
        return this;
    }
}
