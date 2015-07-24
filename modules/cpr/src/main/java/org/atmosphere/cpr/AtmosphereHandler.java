/*
 * Copyright 2015 Async-IO.org
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
package org.atmosphere.cpr;

import java.io.IOException;

/**
 * Implementation of {@link AtmosphereHandler} allows creation of event-driven web applications which are hosted in
 * the browser. An {@link AtmosphereHandler} allows a web application to suspend and resume an HTTP response. An HTTP
 * response can be suspended {@link AtmosphereResource#suspend()} and later resume via {@link  AtmosphereResource#resume()}.
 * Messages can also be shared between suspended responses and their associated {@link AtmosphereHandler} using a
 * {@link Broadcaster}. Any invocation of {@link Broadcaster#broadcast(java.lang.Object)} will allow a suspended
 * response to write the content of the message {@link AtmosphereHandler#onStateChange(org.atmosphere.cpr.AtmosphereResourceEvent)}.
 * <p/>
 * <strong>A class implementing {@link AtmosphereHandler} must be thread safe.</strong>
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
 * of lines of code and take advantage of {@link AtmosphereInterceptor} such as
 * {@link org.atmosphere.interceptor.AtmosphereResourceStateRecovery} and
 * {@link org.atmosphere.interceptor.AtmosphereResourceLifecycleInterceptor}.
 *
 * @author Jeanfrancois Arcand
 */
public interface AtmosphereHandler {

    /**
     * When a client sends a request to its associated {@link AtmosphereHandler}, it can decide if the underlying
     * connection can be suspended (creating a Continuation) or handle the connection synchronously.
     * <p/>
     * It is recommended to only suspend requests for which HTTP method is a GET and use the POST method to send data
     * to the server, without marking the connection as asynchronous.
     *
     * @param resource an {@link AtmosphereResource}
     * @throws java.io.IOException
     */
    void onRequest(AtmosphereResource resource) throws IOException;

    /**
     * This method is invoked when the {@link Broadcaster} executes a broadcast operation. When this method is invoked
     * its associated {@link Broadcaster}, any suspended connection will be allowed to write the data back to its
     * associated clients.
     * <p/>
     * This method will also be invoked when a response get resumed, e.g. when {@link AtmosphereResource#resume} gets
     * invoked. In that case, {@link AtmosphereResourceEvent#isResuming} will return true.
     * <p/>
     * This method will also be invoked when the {@link AtmosphereResource#suspend(long)} expires. In that case,
     * {@link AtmosphereResourceEvent#isResumedOnTimeout} will return <tt>true</tt>.
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
