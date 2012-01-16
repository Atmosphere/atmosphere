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
package org.atmosphere.gwt.poll;

import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.handler.ReflectorServletProcessor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
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
