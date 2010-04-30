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
 * <striong>A class implementing {@link AtmosphereHandler} must be thread safe</strong>
 * <p/>
 * For example, a simple Chat based AtmosphereHandler will take the form of
 * <p/>
 * <p><pre><code>
 * <p/>
 * public AtmosphereResource&lt;HttpServletRequest, HttpServletResponse&gt;
 * onRequest(AtmosphereResource&lt;HttpServletRequest, HttpServletResponse&gt; event) throws IOException {
 * HttpServletRequest req = event.getRequest();
 * HttpServletResponse res = event.getResponse();
 * <p/>
 * res.setContentType("text/html");
 * res.addHeader("Cache-Control", "private");
 * res.addHeader("Pragma", "no-cache");
 * if (req.getMethod().equalsIgnoreCase("GET")) {
 * event.suspend();
 * } else if (req.getMethod().equalsIgnoreCase("POST")) {
 * res.setCharacterEncoding("UTF-8");
 * String action = req.getParameterValues("action")[0];
 * String name = req.getParameterValues("name")[0];
 * <p/>
 * if ("login".equals(action)) {
 * event.getBroadcaster().broadcast(
 * BEGIN_SCRIPT_TAG + toJsonp("System Message from "
 * + event.getWebServerName(), name + " has joined.") + END_SCRIPT_TAG);
 * res.getWriter().write("success");
 * res.getWriter().flush();
 * } else if ("post".equals(action)) {
 * String message = req.getParameterValues("message")[0];
 * event.getBroadcaster().broadcast(BEGIN_SCRIPT_TAG + toJsonp(name, message) + END_SCRIPT_TAG);
 * res.getWriter().write("success");
 * res.getWriter().flush();
 * } else {
 * res.setStatus(422);
 * <p/>
 * res.getWriter().write("success");
 * res.getWriter().flush();
 * }
 * }
 * }
 * <p/>
 * public AtmosphereResource&lt;HttpServletRequest, HttpServletResponse&gt;
 * onStateChange(AtmosphereResource&lt;HttpServletRequest, HttpServletResponse&gt; event) throws IOException {
 * HttpServletRequest req = event.getRequest();
 * HttpServletResponse res = event.getResponse();
 * <p/>
 * if (event.isResuming() || event.isResumedOnTimedout()) {
 * String script = BEGIN_SCRIPT_TAG + "window.parent.app.listen();\n" + END_SCRIPT_TAG;
 * <p/>
 * res.getWriter().write(script);
 * res.getWriter().flush();
 * } else {
 * res.getWriter().write(event.getMessage().toString());
 * res.getWriter().flush();
 * }
 * }
 * <p/>
 * </code></pre></p>
 *
 * @author Jeanfrancois Arcand
 */
public interface AtmosphereHandler<F, G> {

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
    public void onRequest(AtmosphereResource<F, G> resource) throws IOException;


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
    public void onStateChange(AtmosphereResourceEvent<F, G> event) throws IOException;

}
