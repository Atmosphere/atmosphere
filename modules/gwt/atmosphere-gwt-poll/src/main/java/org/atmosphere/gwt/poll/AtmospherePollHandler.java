/*
 *
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 2007-2008 Sun Microsystems, Inc. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License. You can obtain
 * a copy of the License at https://glassfish.dev.java.net/public/CDDL+GPL.html
 * or glassfish/bootstrap/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/bootstrap/legal/LICENSE.txt.
 * Sun designates this particular file as subject to the "Classpath" exception
 * as provided by Sun in the GPL Version 2 section of the License file that
 * accompanied this code.  If applicable, add the following below the License
 * Header, with the fields enclosed by brackets [] replaced by your own
 * identifying information: "Portions Copyrighted [year]
 * [name of copyright owner]"
 *
 * Contributor(s):
 *
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 *
 */
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
