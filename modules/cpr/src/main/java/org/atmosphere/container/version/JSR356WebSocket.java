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
package org.atmosphere.container.version;

import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.websocket.WebSocket;

import javax.websocket.Session;
import java.io.IOException;
import java.nio.ByteBuffer;

public class JSR356WebSocket extends WebSocket {

    private final Session session;

    public JSR356WebSocket(Session session, AtmosphereConfig config) {
        super(config);
        this.session = session;
    }

    @Override
    public boolean isOpen() {
        return session.isOpen();
    }

    @Override
    public WebSocket write(String s) throws IOException {
        session.getBasicRemote().sendText(s);
        return this;
    }

    @Override
    public WebSocket write(byte[] data, int offset, int length) throws IOException {
        session.getBasicRemote().sendBinary(ByteBuffer.wrap(data, offset, length));
        return this;
    }

    @Override
    public void close() {
        logger.trace("WebSocket.close() for AtmosphereResource {}", resource() != null ? resource().uuid() : "null");
        try {
            session.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}