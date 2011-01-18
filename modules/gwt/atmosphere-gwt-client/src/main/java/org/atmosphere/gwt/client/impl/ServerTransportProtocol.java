/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
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
                    logger.log(Level.SEVERE, null, ex);
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
                logger.log(Level.SEVERE, null, ex);
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
                    logger.log(Level.SEVERE, null, ex);
                }
            }
        }
        if (packet.length() > 0) {
            send(packet.toString(), callback);
        }
    }
}
