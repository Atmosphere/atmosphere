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
}
