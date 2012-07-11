package org.atmosphere.gwt.client;

import com.google.gwt.user.client.rpc.AsyncCallback;
import java.util.List;

/**
 *
 * @author p.havelaar
 */
public interface UserInterface {

    // push message back to the server on this connection
    public void post(Object message);

    // push message back to the server on this connection
    public void post(Object message, AsyncCallback<Void> callback);

    // push message back to the server on this connection
    public void post(List<?> messages);

    // push message back to the server on this connection
    public void post(List<?> messages, AsyncCallback<Void> callback);

    // push message back to the server on this connection
    public void broadcast(Object message);

    // push message back to the server on this connection
    public void broadcast(List<?> messages);

    public void start();
    
    public void stop();

}
