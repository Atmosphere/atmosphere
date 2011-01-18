
package org.atmosphere.samples.client;

import com.google.gwt.user.client.rpc.AsyncCallback;

/**
 *
 * @author p.havelaar
 */
public interface PollAsync {

    public void pollDelayed(int milli, AsyncCallback<Event> callback);
}
