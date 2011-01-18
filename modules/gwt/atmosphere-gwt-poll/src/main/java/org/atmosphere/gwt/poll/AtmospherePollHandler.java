package org.atmosphere.gwt.poll;

import java.io.IOException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.handler.ReflectorServletProcessor;

/**
 *
 * @author p.havelaar
 */
public class AtmospherePollHandler extends ReflectorServletProcessor {

    @Override
    public void onStateChange(AtmosphereResourceEvent<HttpServletRequest, HttpServletResponse> event) throws IOException {

        if (event.isCancelled() || event.getMessage() == null) {
            return;
        }
        
        HttpServletRequest request = event.getResource().getRequest();
        if (Boolean.FALSE.equals(request.getAttribute(AtmospherePollService.GWT_SUSPENDED))
            || request.getAttribute(AtmospherePollService.GWT_REQUEST) == null) {
            
            return;
        }
        
        boolean success = false;

        try {
            AtmospherePollService.writeResponse(event.getResource(), event.getMessage());
            success = true;
        } catch (IllegalArgumentException ex) {
            // the message did not have the same type as the return type of the suspended method
        }
        if (success && event.isSuspended()) {
            request.removeAttribute(AtmospherePollService.GWT_SUSPENDED);
            event.getResource().resume();
        }
    }

}
