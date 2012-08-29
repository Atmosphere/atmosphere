/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2012 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://glassfish.dev.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */
package org.atmosphere.container.version;

import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.websocket.WebSocket;
import org.atmosphere.websocket.WebSocketResponseFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

public class Grizzly2WebSocket extends WebSocket {
    
    private static final Logger logger = LoggerFactory.getLogger(Jetty8WebSocket.class);
        private final org.glassfish.grizzly.websockets.WebSocket webSocket;
        private final AtomicBoolean firstWrite = new AtomicBoolean(false);
    
        public Grizzly2WebSocket(org.glassfish.grizzly.websockets.WebSocket webSocket, AtmosphereConfig config) {
            super(config);
            this.webSocket = webSocket;
        }
    
        /**
         * {@inheritDoc}
         */
        @Override
        public WebSocket writeError(AtmosphereResponse r, int errorCode, String message) throws IOException {
            if (!firstWrite.get()) {
                logger.debug("The WebSocket handshake succeeded but the dispatched URI failed {}:{}. " +
                        "The WebSocket connection is still open and client can continue sending messages.", message, errorCode);
            } else {
                logger.debug("{} {}", errorCode, message);
            }
            return this;
        }
    
        /**
         * {@inheritDoc}
         */
        @Override
        public WebSocket redirect(AtmosphereResponse r, String location) throws IOException {
            logger.error("redirect not supported");
            return this;
        }
    
        /**
         * {@inheritDoc}
         */
        @Override
        public WebSocket write(AtmosphereResponse r, String data) throws IOException {
            if (binaryWrite) {
                byte[] b = webSocketResponseFilter.filter(r, data).getBytes(resource().getResponse().getCharacterEncoding());
                if (b != null) webSocket.send(b);
            } else {
                String s = webSocketResponseFilter.filter(r, data);
                if (s != null)  webSocket.send(s);
            }
            lastWrite = System.currentTimeMillis();
            return this;
        }
    
        /**
         * {@inheritDoc}
         */
        @Override
        public WebSocket write(AtmosphereResponse r, byte[] data) throws IOException {
            if (binaryWrite) {
                byte[] b = webSocketResponseFilter.filter(r, data);
                if (b != null) webSocket.send(b);
            } else {
                String s = webSocketResponseFilter.filter(r, new String(data));
                if (s != null) webSocket.send(s);
    
            }
            lastWrite = System.currentTimeMillis();
            return this;
        }
    
        /**
         * {@inheritDoc}
         */
        @Override
        public WebSocket write(AtmosphereResponse r, byte[] data, int offset, int length) throws IOException {
            if (binaryWrite) {
                if (!WebSocketResponseFilter.NoOpsWebSocketResponseFilter.class.isAssignableFrom(webSocketResponseFilter.getClass())) {
                    byte[] b = webSocketResponseFilter.filter(r, data, offset, length);
                    if (b != null) webSocket.send(b);
                } else {
                    webSocket.send(Arrays.copyOfRange(data, offset, length));
                }
            } else {
                String s = webSocketResponseFilter.filter(r, new String(data, offset, length));
                if (s != null) webSocket.send(s);
            }
            lastWrite = System.currentTimeMillis();
            return this;
        }
    
        /**
         * {@inheritDoc}
         */
        @Override
        public void close(AtmosphereResponse r) throws IOException {
            webSocket.close();
        }
    
        /**
         * {@inheritDoc}
         */
        @Override
        public WebSocket flush(AtmosphereResponse r) throws IOException {
            return this;
        }
    
}
