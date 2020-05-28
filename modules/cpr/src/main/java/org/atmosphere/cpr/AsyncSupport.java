/*
 * Copyright 2008-2020 Async-IO.org
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

import org.atmosphere.container.BlockingIOCometSupport;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import java.awt.event.ActionEvent;
import java.io.IOException;

/**
 * Atmosphere's supported WebServer must implement this interface in order to be auto detected by the
 * {@link AtmosphereFramework}. If the {@link AtmosphereFramework} fail to detect the {@link AsyncSupport}, it will
 * use a blocking thread approach to emulate Comet using the {@link BlockingIOCometSupport}.
 * <p/>
 * Framework designers or Atmosphere application developers can also add their own implementation of that class by
 * referencing their class within the atmosphere.xml file:
 * <p><pre><code>
 * <&lt;atmosphere-handler ... comet-support="your.class.name"&gt;
 * </code></pre></p>
 *
 * @author Jeanfrancois Arcand
 */
public interface AsyncSupport<E extends AtmosphereResource> {

    /**
     * Return the name of the Java Web Server.
     *
     * @return the name of the Java Web Server.
     */
    String getContainerName();

    /**
     * Initialize the WebServer using the {@link ServletConfig}
     *
     * @param sc the {@link ServletConfig}
     */
    void init(ServletConfig sc) throws ServletException;

    /**
     * Serve the {@link AtmosphereRequest} and the {@link AtmosphereResponse} and return
     * the appropriate {@link Action}.
     *
     * @param req the {@link AtmosphereRequest}
     * @param res the {@link AtmosphereResponse}
     * @return the {@link Action} that was manipulated by the {@link AtmosphereHandler}
     */
    Action service(AtmosphereRequest req, AtmosphereResponse res) throws IOException, ServletException;

    /**
     * Process an {@link Action} from an {@link ActionEvent} operation like suspend, resume or timed out.
     *
     * @param actionEvent An instance of {@link Action}
     */
    void action(E actionEvent);

    /**
     * Return true if this implementation supports the websocket protocol.
     *
     * @return true if supported
     */
    boolean supportWebSocket();

    /**
     * Complete and close the connection associated with an implementation of {@link org.atmosphere.cpr.AtmosphereResource}
     * @param r {@link org.atmosphere.cpr.AtmosphereResource}
     * @return this
     */
    AsyncSupport complete(E r);
}
