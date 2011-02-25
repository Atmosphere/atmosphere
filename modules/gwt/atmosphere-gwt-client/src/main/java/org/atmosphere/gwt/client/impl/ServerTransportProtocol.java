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
 *
 */

package org.atmosphere.gwt.client.impl;

import com.google.gwt.user.client.rpc.AsyncCallback;
import com.google.gwt.user.client.rpc.SerializationException;
import java.io.Serializable;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author p.havelaar
 */
abstract public class ServerTransportProtocol implements ServerTransport {

    abstract void send(String message, AsyncCallback<Void> callback);
    abstract String serialize(Serializable message) throws SerializationException;
    
    private Logger logger = Logger.getLogger(getClass().getName());
    
    private AsyncCallback<Void> defaultCallback = new AsyncCallback<Void>() {
        @Override
        public void onFailure(Throwable caught) {
            logger.log(Level.SEVERE, "Failed send", caught);
        }
        @Override
        public void onSuccess(Void result) {
            if (logger.isLoggable(Level.FINEST)) {
                logger.log(Level.FINEST, "Send succesfull");
            }
        }
    };
    
    @Override
    public void disconnect() {
        send("c\nd\n\n", defaultCallback);
    }

    @Override
    public void broadcast(Serializable message) {
        if (message instanceof String) {
            send("s\nb"+message+"\n\n", defaultCallback);
        } else {
            try {
                send("o\nb"+serialize(message)+"\n\n", defaultCallback);
            } catch (SerializationException ex) {
                logger.log(Level.SEVERE, "Failed to serialize message", ex);
            }
        }
    }

    @Override
    public void broadcast(List<Serializable> messages) {
        StringBuilder packet = new StringBuilder();
        for (Serializable message : messages) {
            if (message instanceof String) {
                packet.append("s\nb").append(message).append("\n\n");
            } else {
                try {
                    packet.append("o\nb").append(serialize(message)).append("\n\n");
                } catch (SerializationException ex) {
                    logger.log(Level.SEVERE, "Failed to serialize message", ex);
                }
            }
        }
        if (packet.length() > 0) {
            send(packet.toString(), defaultCallback);
        }
    }

    @Override
    public void post(Serializable message, AsyncCallback<Void> callback) {
        if (message instanceof String) {
            send("s\np"+message+"\n\n", callback);
        } else {
            try {
                send("o\np"+serialize(message)+"\n\n", callback);
            } catch (SerializationException ex) {
                logger.log(Level.SEVERE, "Failed to serialize message", ex);
            }
        }
    }

    @Override
    public void post(List<Serializable> messages, AsyncCallback<Void> callback) {
        StringBuilder packet = new StringBuilder();
        for (Serializable message : messages) {
            if (message instanceof String) {
                packet.append("s\np").append(message).append("\n\n");
            } else {
                try {
                    packet.append("o\np").append(serialize(message)).append("\n\n");
                } catch (SerializationException ex) {
                    logger.log(Level.SEVERE, "Failed to serialize message", ex);
                }
            }
        }
        if (packet.length() > 0) {
            send(packet.toString(), callback);
        }
    }
}
