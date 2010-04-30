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

package org.atmosphere.samples.counter;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.concurrent.atomic.AtomicInteger;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereResource;

/**
 * Simple application that demonstrate how a Comet long poll request can be implemented.
 * Mainly, the client send a GET, the {@link AtmosphereHandler} suspend the connection. As
 * soon a POST request arrive, the underlying response is resumed.
 *
 * @author Jeanfrancois Arcand
 */
public class CounterLongPollingAtmosphereHandler implements AtmosphereHandler<HttpServletRequest, HttpServletResponse> {
      
    private final AtomicInteger counter = new AtomicInteger();

    /**
     * On GET, suspend the conneciton. On POST, resume the connection.
     * @param event
     * @return
     * @throws IOException
     */
    public void onRequest(AtmosphereResource<HttpServletRequest, HttpServletResponse> event) throws IOException {

        if (event.getRequest().getMethod().equals("GET")){
            event.suspend();
        } else {
            counter.incrementAndGet();
            // Nothing to broadcast, but fire an event.
            event.getBroadcaster().broadcast("");

            PrintWriter writer = event.getResponse().getWriter();
            writer.write("success");
            writer.flush();
        }
    }

    /**
     * Resume the underlying response on the first {@link Broadcast}
     * @param event
     * @return
     * @throws IOException
     */
    public void onStateChange(AtmosphereResourceEvent<HttpServletRequest,
            HttpServletResponse> event) throws IOException {

        int count = counter.get();

        // Client closed the connection.
        if (event.isCancelled()){
            return;
        }

        event.getResource().getResponse().addHeader("X-JSON", "{\"counter\":" + count + " }");
        PrintWriter writer = event.getResource().getResponse().getWriter();
        writer.write("success");
        writer.flush();

        if (!event.isResumedOnTimeout()){
            event.getResource().resume();
        } 
    }
}
