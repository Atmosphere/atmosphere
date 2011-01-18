
package org.atmosphere.samples.client;

import com.google.gwt.user.client.rpc.RemoteService;
import com.google.gwt.user.client.rpc.RemoteServiceRelativePath;

/**
 *
 * @author p.havelaar
 */
@RemoteServiceRelativePath("gwtPoll")
public interface Poll extends RemoteService {

    public Event pollDelayed(int milli);
}
