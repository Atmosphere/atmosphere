/*
 * Copyright 2017 Async-IO.org
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
package org.atmosphere.runtime;


import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import java.awt.event.ActionEvent;
import java.io.IOException;

public interface AsyncSupport<E extends AtmosphereResource> {

    /**
     * Return the name of the Java Web Server.
     *
     * @return the name of the Java Web Server.
     */
    public String getContainerName();

    /**
     * Initialize the WebServer using the {@link ServletConfig}
     *
     * @param sc the {@link ServletConfig}
     * @throws javax.servlet.ServletException
     */
    public void init(ServletConfig sc) throws ServletException;

    /**
     * Serve the {@link AtmosphereRequest} and the {@link AtmosphereResponse} and return
     * the appropriate {@link Action}.
     *
     * @param req the {@link AtmosphereRequest}
     * @param res the {@link AtmosphereResponse}
     * @return the {@link Action} that was manipulated by the {@link AtmosphereHandler}
     * @throws java.io.IOException
     * @throws javax.servlet.ServletException
     */
    public Action service(AtmosphereRequest req, AtmosphereResponse res) throws IOException, ServletException;

    /**
     * Process an {@link Action} from an {@link ActionEvent} operation like suspend, resume or timed out.
     *
     * @param actionEvent An instance of {@link Action}
     */
    public void action(E actionEvent);

    /**
     * Return true if this implementation supports the websocket protocol.
     *
     * @return true if supported
     */
    public boolean supportWebSocket();

    /**
     * Complete and close the connection associated with an implementation of {@link org.atmosphere.runtime.AtmosphereResource}
     * @param r {@link org.atmosphere.runtime.AtmosphereResource}
     * @return this
     */
    public AsyncSupport complete(E r);
}
