/*
 * Copyright 2011 Jeanfrancois Arcand
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
 */
public interface AsyncIOWriter {

    /**
     * Redirect a WebSocket request to another location
     * @param location
     * @throws IOException
     */
    void redirect(String location) throws IOException;

    /**
     * Write an error code
     * @param errorCode the error code
     * @param message
     * @throws IOException
     */
    void writeError(int errorCode, String message) throws IOException;

    /**
     * Write a WebSocket message
     *
     * @param data the WebSocket message
     * @throws java.io.IOException
     */
    void write(String data) throws IOException;

    /**
     * Write a WebSocket message
     *
     * @param data the WebSocket message
     * @throws IOException
     */
    void write(byte[] data) throws IOException;

    /**
     * Write a WebSocket message
     *
     * @param data   the WebSocket message
     * @param offset offset of the message
     * @param length length if the message
     * @throws IOException
     */
    void write(byte[] data, int offset, int length) throws IOException;

}
