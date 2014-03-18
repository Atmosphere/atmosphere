/*
 * Copyright 2014 Jeanfrancois Arcand
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

import org.atmosphere.cache.BroadcastMessage;
import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.WebSocketProcessorFactory;
import org.atmosphere.websocket.WebSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.websocket.SendHandler;
import javax.websocket.SendResult;
import javax.websocket.Session;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.Semaphore;

/**
 * Asynchronous based {@link Session} websocket
 *
 * @author Jeanfrancois Arcand
 */
public class JSR356WebSocket extends WebSocket {

    private final Logger logger = LoggerFactory.getLogger(JSR356WebSocket.class);
    private final Session session;
    private final Semaphore semaphore = new Semaphore(1, true);

    public JSR356WebSocket(Session session, AtmosphereConfig config) {
        super(config);
        this.session = session;
        // https://issues.apache.org/bugzilla/show_bug.cgi?id=56026
        String s = config.getInitParameter(ApplicationConfig.WEBSOCKET_IDLETIME);
        if (s != null) {
            session.getAsyncRemote().setSendTimeout(Integer.valueOf(s));
        }
    }

    @Override
    public boolean isOpen() {
        return session.isOpen();
    }

    @Override
    public WebSocket write(String s) throws IOException {
        try {
            try {
                semaphore.acquireUninterruptibly();
                session.getAsyncRemote().sendText(s, new WriteResult(resource(), s));
            } catch (IllegalStateException e) {
                semaphore.release();
                throw e;
            }
        } catch (NullPointerException e) {
            patchGlassFish(e);
        }
        return this;
    }

    @Override
    public WebSocket write(byte[] data, int offset, int length) throws IOException {
        try {
            semaphore.acquireUninterruptibly();
            ByteBuffer b = ByteBuffer.wrap(data, offset, length);
            session.getAsyncRemote().sendBinary(ByteBuffer.wrap(data, offset, length),
                    new WriteResult(resource(), b.array()));
        } catch (NullPointerException e) {
            patchGlassFish(e);
            semaphore.release();
        } catch (IllegalStateException e) {
            semaphore.release();
            throw e;
        }
        return this;
    }

    void patchGlassFish(NullPointerException e) {
        // https://java.net/jira/browse/TYRUS-175
        logger.trace("", e);
        WebSocketProcessorFactory.getDefault().getWebSocketProcessor(config().framework()).close(this, 1002);
    }

    @Override
    public void close() {
        logger.trace("WebSocket.close() for AtmosphereResource {}", resource() != null ? resource().uuid() : "null");
        try {
            session.close();
            // Tomcat may throw  https://gist.github.com/jfarcand/6702738
        } catch (Exception e) {
            logger.trace("", e);
        }
    }

    private final class WriteResult implements SendHandler {

        private final AtmosphereResource r;
        private final Object message;

        private WriteResult(AtmosphereResource r, Object message) {
            this.r = r;
            this.message = message;
        }

        @Override
        public void onResult(SendResult result) {
            semaphore.release();
            if (!result.isOK() || result.getException() != null) {
                logger.trace("WebSocket {} failed to write {}", r, message);
                Broadcaster b = r.getBroadcaster();
                b.getBroadcasterConfig().getBroadcasterCache().addToCache(b.getID(), r.uuid(), new BroadcastMessage(message));
            }
        }
    }
}