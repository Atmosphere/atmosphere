/*
 *
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 2007-2008 Sun Microsystems, Inc. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License. You can obtain
 * a copy of the License at https://glassfish.dev.java.net/public/CDDL+GPL.html
 * or glassfish/bootstrap/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/bootstrap/legal/LICENSE.txt.
 * Sun designates this particular file as subject to the "Classpath" exception
 * as provided by Sun in the GPL Version 2 section of the License file that
 * accompanied this code.  If applicable, add the following below the License
 * Header, with the fields enclosed by brackets [] replaced by your own
 * identifying information: "Portions Copyrighted [year]
 * [name of copyright owner]"
 *
 * Contributor(s):
 *
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
package org.atmosphere.websocket.container;

import org.atmosphere.websocket.WebSocket;
import org.eclipse.jetty.websocket.WebSocket.Outbound;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Jetty 7.1/2 & 8 < M3 WebSocket support.
 *
 * @author Jeanfrancois Arcand
 */
public class JettyWebSocket implements WebSocket {

    private static final Logger logger = LoggerFactory.getLogger(JettyWebSocket.class);
    private final Outbound outbound;

    private AtomicBoolean webSocketLatencyCheck = new AtomicBoolean(false);

    public JettyWebSocket(Outbound outbound) {
        this.outbound = outbound;
    }

    public void writeError(int errorCode, String message) throws IOException {
    }

    public void redirect(String location) throws IOException {
    }

    public void write(byte frame, String data) throws IOException {
        if (!outbound.isOpen()) throw new IOException("Connection remotely closed");
        logger.trace("WebSocket.write()");
        outbound.sendMessage(frame, data);
    }

    public void write(byte frame, byte[] data) throws IOException {
        if (!outbound.isOpen()) throw new IOException("Connection remotely closed");
        logger.trace("WebSocket.write()");
        outbound.sendMessage(frame, data, 0, data.length);
    }

    public void write(byte frame, byte[] data, int offset, int length) throws IOException {
        if (!outbound.isOpen()) throw new IOException("Connection remotely closed");
        logger.trace("WebSocket.write()");
        outbound.sendMessage(frame, data, offset, length);
    }

    public void close() throws IOException {
        outbound.disconnect();
    }

}
