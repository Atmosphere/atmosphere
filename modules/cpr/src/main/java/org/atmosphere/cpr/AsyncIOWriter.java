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
 * An Asynchronous I/O Writer is used by a {@link AtmosphereResponse} when writing data.
 *
 * @author Jeanfrancois Arcand
 */
public interface AsyncIOWriter {

    /**
     * Redirect a WebSocket request to another location.
     *
     * @param location
     * @throws IOException
     */
    AsyncIOWriter redirect(AtmosphereResponse r, String location) throws IOException;

    /**
     * Write an error code.
     *
     * @param errorCode the error code
     * @param message
     * @throws IOException
     */
    AsyncIOWriter writeError(AtmosphereResponse r, int errorCode, String message) throws IOException;

    /**
     * Write a WebSocket message.
     *
     * @param data the WebSocket message
     * @throws java.io.IOException
     */
    AsyncIOWriter write(AtmosphereResponse r, String data) throws IOException;

    /**
     * Write a WebSocket message.
     *
     * @param data the WebSocket message
     * @throws IOException
     */
    AsyncIOWriter write(AtmosphereResponse r, byte[] data) throws IOException;

    /**
     * Write a WebSocket message.
     *
     * @param data   the WebSocket message
     * @param offset offset of the message
     * @param length length of the message
     * @throws IOException
     */
    AsyncIOWriter write(AtmosphereResponse r, byte[] data, int offset, int length) throws IOException;

    /**
     * Close the underlying connection.
     */
    void close(AtmosphereResponse r) throws IOException;

    /**
     * Flush the buffered content.
     */
    AsyncIOWriter flush(AtmosphereResponse r) throws IOException;
}
