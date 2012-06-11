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
 */

package org.atmosphere.handler;

import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.Broadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;

import static org.atmosphere.cpr.ApplicationConfig.PROPERTY_USE_STREAM;

/**
 * Simple {@link AtmosphereHandler} that reflect every call to
 * {@link Broadcaster#broadcast}, e.g sent the broadcasted event back to the remote client. All broadcast will be by default returned
 * as it is to the suspended {@link org.atmosphere.cpr.AtmosphereResponse#getOutputStream}
 * or {@link org.atmosphere.cpr.AtmosphereResponse#getWriter()}
 *
 * @author Jean-francois Arcand
 */
public abstract class AbstractReflectorAtmosphereHandler implements AtmosphereHandler {

    private static final Logger logger = LoggerFactory.getLogger(AbstractReflectorAtmosphereHandler.class);

    /**
     * Write the {@link AtmosphereResourceEvent#getMessage()} back to the client using
     * the {@link org.atmosphere.cpr.AtmosphereResponse#getOutputStream()} or {@link org.atmosphere.cpr.AtmosphereResponse#getWriter()}.
     * If a {@link org.atmosphere.cpr.Serializer} is defined, it will be invoked and the writ operation
     * will be delegated to to it.
     * <p/>
     * By default, this method will try to use {@link org.atmosphere.cpr.AtmosphereResponse#getWriter()}.
     *
     * @param event the {@link AtmosphereResourceEvent#getMessage()}
     * @throws java.io.IOException
     */
    public void onStateChange(AtmosphereResourceEvent event)
            throws IOException {

        Object message = event.getMessage();
        if (message == null || event.isCancelled() || event.getResource().getRequest().destroyed()) return;

        if (event.getResource().getSerializer() != null) {
            try {
                event.getResource().getSerializer().write(event.getResource().getResponse().getOutputStream(), message);
            } catch (Throwable ex) {
                logger.warn("Serializer exception: message: " + message, ex);
                throw new IOException(ex);
            }
        } else {
            boolean isUsingStream = (Boolean) event.getResource().getRequest().getAttribute(PROPERTY_USE_STREAM);

            if (!isUsingStream) {
                try {
                    event.getResource().getResponse().getWriter();
                } catch (IllegalStateException e) {
                    isUsingStream = true;
                }
            }

            if (message instanceof List) {
                for (String s : (List<String>) message) {
                    if (isUsingStream) {
                        event.getResource().getResponse().getOutputStream().write(s.getBytes("UTF-8"));
                        event.getResource().getResponse().getOutputStream().flush();
                    } else {
                        event.getResource().getResponse().getWriter().write(s);
                        event.getResource().getResponse().getWriter().flush();
                    }
                }
            } else {
                if (isUsingStream) {
                    event.getResource().getResponse().getOutputStream().write(message.toString().getBytes("UTF-8"));
                    event.getResource().getResponse().getOutputStream().flush();
                } else {
                    event.getResource().getResponse().getWriter().write(message.toString());
                    event.getResource().getResponse().getWriter().flush();
                }
            }

            Boolean resumeOnBroadcast = event.getResource().resumeOnBroadcast();
            if (!resumeOnBroadcast) {
                // For legacy reason, check the attribute as well
                Object o = event.getResource().getRequest().getAttribute(ApplicationConfig.RESUME_ON_BROADCAST);
                if (o != null && Boolean.class.isAssignableFrom(o.getClass())) {
                    resumeOnBroadcast = Boolean.class.cast(o);
                }
            }

            if (resumeOnBroadcast != null && resumeOnBroadcast) {
                event.getResource().resume();
            }
        }
    }

}
