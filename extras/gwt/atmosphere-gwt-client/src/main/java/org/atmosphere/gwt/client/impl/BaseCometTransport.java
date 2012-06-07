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
/*
 * Copyright 2009 Richard Zschech.
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

import com.google.gwt.core.client.Duration;
import com.google.gwt.core.client.GWT;
import com.google.gwt.http.client.Request;
import com.google.gwt.http.client.RequestBuilder;
import com.google.gwt.http.client.RequestCallback;
import com.google.gwt.http.client.RequestException;
import com.google.gwt.http.client.Response;
import com.google.gwt.user.client.rpc.AsyncCallback;
import com.google.gwt.user.client.rpc.SerializationException;
import com.google.gwt.user.client.rpc.StatusCodeException;
import org.atmosphere.gwt.client.AtmosphereClient;
import org.atmosphere.gwt.client.AtmosphereGWTSerializer;
import org.atmosphere.gwt.client.AtmosphereListener;
import org.atmosphere.gwt.shared.SerialMode;
import org.atmosphere.gwt.shared.Constants;

import java.io.Serializable;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * This is the base class for the comet implementations
 *
 * @author Richard Zschech
 */
public abstract class BaseCometTransport implements CometTransport {

    protected AtmosphereClient client;
    protected AtmosphereListener listener;
    protected ServerTransport serverTransport;
    protected int connectionId;
    private static final Logger logger = Logger.getLogger(BaseCometTransport.class.getName());

    @Override
    public void disconnect() {
        listener.onBeforeDisconnected();
        getServerTransport().disconnect();
    }

    @Override
    public void post(Object message, AsyncCallback<Void> callback) {
        getServerTransport().post(message, callback);
    }

    @Override
    public void post(List messages, AsyncCallback<Void> callback) {
        getServerTransport().post(messages, callback);
    }

    @Override
    public void broadcast(Object message) {
        getServerTransport().broadcast(message);
    }

    @Override
    public void broadcast(List messages) {
        getServerTransport().broadcast(messages);
    }

    @Override
    public void initiate(AtmosphereClient client, AtmosphereListener listener) {
        this.client = client;
        this.listener = listener;
    }

    protected ServerTransport getServerTransport() {
        if (serverTransport == null) {
            serverTransport = new RPCServerTransport();
        }
        return serverTransport;
    }

    protected class RPCServerTransport extends ServerTransportProtocol {

        @Override
        void send(String message, final AsyncCallback<Void> callback) {
            RequestBuilder request = new RequestBuilder(RequestBuilder.POST, serviceUrl());
            try {
                logger.fine("Sending message to server: " + message);
                request.sendRequest(message, new RequestCallback() {
                    @Override
                    public void onResponseReceived(Request request, Response response) {
                        // when a connection is abruptly closed (for instance when a user presses F5
                        // the statuscode seems to be 0, the call could have arrived at the server though
                        if (response.getStatusCode() != Response.SC_OK
                                && response.getStatusCode() != 0) {
                            logger.log(Level.SEVERE, "Failed to send server message: [" + response.getStatusText() + "," + response.getStatusCode() + "]");
                            callback.onFailure(new StatusCodeException(response.getStatusCode(), response.getStatusText()));
                        } else {
                            callback.onSuccess(null);
                        }
                    }

                    @Override
                    public void onError(Request request, Throwable exception) {
                        callback.onFailure(exception);
                    }
                });
            } catch (RequestException ex) {
                callback.onFailure(ex);
            }
        }

        @Override
        public String serialize(Object message) throws SerializationException {
            return client.getSerializer().serialize(message);
        }

        protected String serviceUrl() {
            int i = client.getUrl().indexOf('?');
            String serviceUrl = (i > 0 ? client.getUrl().substring(0, i) : client.getUrl())
                    + "?servertransport=rpcprotocol&connectionID=" + connectionId;
            
            serviceUrl = addUrlParameter(serviceUrl, Constants.CLIENT_SERIALZE_MODE_PARAMETER, client.getSerializer().getPushMode().name());
            return serviceUrl;
        }

    }

    protected Object parse(String message) throws SerializationException {
        if (message == null || message.isEmpty()) {
            return null;
        }
        AtmosphereGWTSerializer serializer = client.getSerializer();
        if (serializer == null) {
            throw new SerializationException("Can not deserialize message without serializer: " + message);
        } else {
            return serializer.deserialize(message);
        }
    }


    public String getUrl(int connectionCount) {
        String url = client.getUrl();
        if (client.getSerializer() != null) {
            if (client.getSerializer().getMode() == SerialMode.DE_RPC) {
                url = addUrlParameter(url, Constants.MODULE_BASE_PARAMETER, 
                    GWT.getModuleBaseURL() + '&' + Constants.STRONG_NAME_PARAMETER + '=' + GWT.getPermutationStrongName());
            }
            url = addUrlParameter(url, Constants.CLIENT_DESERIALZE_MODE_PARAMETER, client.getSerializer().getMode().name());
            url = addUrlParameter(url, Constants.CLIENT_SERIALZE_MODE_PARAMETER, client.getSerializer().getPushMode().name());
        }
        String className = getClass().getName();
        className = className.substring(className.lastIndexOf('.') + 1);
        String transport = className.substring(0, className.indexOf("CometTransport"));
        return addUrlParameter(url, "t", Integer.toString((int) (Duration.currentTimeMillis() % Integer.MAX_VALUE), Character.MAX_RADIX).toUpperCase()
                + "&c=" + connectionCount
                + "&tr=" + transport);
    }
    
    public String addUrlParameter(String url, String name, String value) {
        return url + (url.contains("?") ? "&" : "?") + name + "=" + value;
    }

}
