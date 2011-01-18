/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package org.atmosphere.gwt.client.impl;

import com.google.gwt.user.client.rpc.AsyncCallback;
import java.io.Serializable;
import java.util.List;

/**
 *
 * @author p.havelaar
 */
public interface ServerTransport {

    public void disconnect();
    public void post(Serializable message, AsyncCallback<Void> callback);
    public void post(List<Serializable> messages, AsyncCallback<Void> callback);
    public void broadcast(Serializable message);
    public void broadcast(List<Serializable> messages);
}
