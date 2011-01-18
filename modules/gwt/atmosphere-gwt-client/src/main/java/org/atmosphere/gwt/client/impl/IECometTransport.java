/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package org.atmosphere.gwt.client.impl;

import com.google.gwt.user.client.rpc.AsyncCallback;
import org.atmosphere.gwt.client.AtmosphereClient;
import org.atmosphere.gwt.client.AtmosphereListener;
import java.io.Serializable;
import java.util.List;

/**
 *
 * @author p.havelaar
 */
public class IECometTransport implements CometTransport {

    CometTransport transport;

    public IECometTransport() {
        if (XDomainRequest.isSupported()) {
            transport = new IEXDomainRequestCometTransport();
        } else {
            transport = new IEHTMLFileCometTransport();
        }
    }

    @Override
    public void connect(int connectionCount) {
        transport.connect(connectionCount);
    }

    @Override
    public void disconnect() {
        transport.disconnect();
    }

    @Override
    public void initiate(AtmosphereClient client, AtmosphereListener listener) {
        transport.initiate(client, listener);
    }

    @Override
    public void post(Serializable message, AsyncCallback<Void> callback) {
        transport.post(message, callback);
    }

    @Override
    public void post(List<Serializable> messages, AsyncCallback<Void> callback) {
        transport.post(messages, callback);
    }

    @Override
    public void broadcast(Serializable message) {
        transport.broadcast(message);
    }

    @Override
    public void broadcast(List<Serializable> messages) {
        transport.broadcast(messages);
    }
    
}
