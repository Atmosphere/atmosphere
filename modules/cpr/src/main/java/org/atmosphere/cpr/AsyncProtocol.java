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

/**
 * An interface used by an {@link AtmosphereResponse} to manipulate the response before it gets delegated to an {@link AsyncIOWriter}
 *
 * @author Jeanfrancois Arcand
 */
public interface AsyncProtocol {

    /**
     * Return true if this implementation will manipulate/change the WebSocket message;
     * @return true if this implementation will manipulate/change the WebSocket message;
     */
    boolean inspectResponse();

    /**
     * Give a chance to a {@link AsyncProtocol} to modify the final response using a fake {@link javax.servlet.http.HttpServletResponse} that was
     * dispatched to a ServletContainer and it's framework or application running it.
     *
     * This method is only invoked when {@link AtmosphereResponse} is about to write some data.
     *
     * @param res {@link javax.servlet.http.HttpServletResponse}
     * @param message the String message;
     * @return a new response String
     */
    String handleResponse(AtmosphereResponse res, String message);

    /**
     * Give a chance to a {@link AsyncProtocol} to modify the final response using a fake {@link javax.servlet.http.HttpServletResponse} that was
     * dispatched to a ServletContainer and it's framework or application running it.
     *
     * This method is only invoked when {@link AtmosphereResponse} is about to write some data.

     *
     * @param res {@link javax.servlet.http.HttpServletResponse}
     * @param message the WebSocket message;
     * @param offset offset of the message
     * @param length the length of the message
     * @return a new byte[] message.
     */
    byte[] handleResponse(AtmosphereResponse res, byte[] message, int offset, int length);
}
