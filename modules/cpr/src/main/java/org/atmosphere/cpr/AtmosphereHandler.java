/*
 * Copyright 2013 Jeanfrancois Arcand
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
package org.atmosphere.cpr;

import java.io.IOException;

/**
 * Implementation of {@link AtmosphereHandler} allows creation of
 * event-driven web applications which are hosted in the browser.
 * An {@link AtmosphereHandler} allows a web application to suspend and resume
 * an http response. An http response can be suspended {@link AtmosphereResource#suspend()}
 * and later resume via {@link  AtmosphereResource#resume()}. Messages can also
 * be shared between suspended response and their associated {@link AtmosphereHandler}
 * using a {@link Broadcaster}. Any invocation of {@link Broadcaster#broadcast(java.lang.Object)}
 * will allow a suspended response to write the content of the message
 * {@link AtmosphereHandler#onStateChange(org.atmosphere.cpr.AtmosphereResourceEvent)}.
 * <p/>
 * <strong>A class implementing {@link AtmosphereHandler} must be thread safe</strong>
 * <p/>
 * For example, a simple pubsub based AtmosphereHandler will take the form of
 * <p/>
<blockquote><pre>
 public class AtmosphereHandlerPubSub extends AbstractReflectorAtmosphereHandler {

    public void onRequest(AtmosphereResource r) throws IOException {

        AtmosphereRequest req = r.getRequest();
        AtmosphereResponse res = r.getResponse();
        String method = req.getMethod();

        // Suspend the response.
        if ("GET".equalsIgnoreCase(method)) {
            // Log all events on the console, including WebSocket events.
            r.addEventListener(new WebSocketEventListenerAdapter());

            res.setContentType("text/html;charset=ISO-8859-1");

            Broadcaster b = lookupBroadcaster(req.getPathInfo());
            r.setBroadcaster(b).suspend();
        } else if ("POST".equalsIgnoreCase(method)) {
            Broadcaster b = lookupBroadcaster(req.getPathInfo());

            String message = req.getReader().readLine();

            if (message != null && message.indexOf("message") != -1) {
                b.broadcast(message.substring("message=".length()));
            }
        }
    }

    public void destroy() {
    }

    Broadcaster lookupBroadcaster(String pathInfo) {
        String[] decodedPath = pathInfo.split("/");
        Broadcaster b = BroadcasterFactory.getDefault().lookup(decodedPath[decodedPath.length - 1], true);
        return b;
    }

}</pre></blockquote>
 *
 * It is recommended to use the {@link org.atmosphere.config.service.AtmosphereHandlerService}
 * or {@link org.atmosphere.config.service.ManagedService} annotation to reduce the number
 * of line of code and take advantage of {@link AtmosphereInterceptor} such as
 * {@link org.atmosphere.interceptor.AtmosphereResourceStateRecovery} and
 * {@link org.atmosphere.interceptor.AtmosphereResourceLifecycleInterceptor}
 *
 * @author Jeanfrancois Arcand
 */
public interface AtmosphereHandler {

    /**
     * When a client send a request to its associated {@link AtmosphereHandler}, it can decide
     * if the underlying connection can be suspended (creating a Continuation)
     * or handle the connection synchronously.
     * <p/>
     * It is recommended to only suspend request for which HTTP method is a GET
     * and use the POST method to send data to the server, without marking the
     * connection as asynchronous.
     *
     * @param resource an {@link AtmosphereResource}
     * @throws java.io.IOException
     */
    void onRequest(AtmosphereResource resource) throws IOException;


    /**
     * This method is invoked when the {@link Broadcaster} execute a broadcast
     * operations. When this method is invoked its associated {@link Broadcaster}, any
     * suspended connection will be allowed to write the data back to its
     * associated clients. <br>
     * This method will also be invoked when a response get resumed,
     * e.g. when {@link AtmosphereResource#resume} gets invoked. In that case,
     * {@link AtmosphereResourceEvent#isResuming} will return true.<br>
     * This method will also be invoked when the {@link AtmosphereResource#suspend(long)}
     * expires. In that case, {@link AtmosphereResourceEvent#isResumedOnTimeout} will return
     * <tt>true</tt>
     *
     * @param event an {@link AtmosphereResourceEvent}
     * @throws java.io.IOException
     */
    void onStateChange(AtmosphereResourceEvent event) throws IOException;


    /**
     * Destroy this handler
     */
    void destroy();
}
