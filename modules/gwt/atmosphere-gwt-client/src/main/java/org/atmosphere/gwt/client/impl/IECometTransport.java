
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
