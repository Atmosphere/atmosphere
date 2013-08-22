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
 */

package org.atmosphere.handler;

import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.Broadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
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
     * If a {@link org.atmosphere.cpr.Serializer} is defined, it will be invoked and the write operation
     * will be delegated to it.
     * <p/>
     * By default, this method will try to use {@link org.atmosphere.cpr.AtmosphereResponse#getWriter()}.
     *
     * @param event the {@link AtmosphereResourceEvent#getMessage()}
     * @throws java.io.IOException
     */
    public void onStateChange(AtmosphereResourceEvent event)
            throws IOException {

        Object message = event.getMessage();
        AtmosphereResource resource = event.getResource();
        AtmosphereResponse r = resource.getResponse();
        
        if (message == null) {
            logger.trace("Message was null for AtmosphereEvent {}", event);
            return;
        }

        if (resource.getSerializer() != null) {
            try {

                if (message instanceof List) {
                    for (Object s : (List<Object>) message) {
                         resource.getSerializer().write(resource.getResponse().getOutputStream(), s);
                    }
                }  else {
                    resource.getSerializer().write(resource.getResponse().getOutputStream(), message);
                }
            } catch (Throwable ex) {
                logger.warn("Serializer exception: message: " + message, ex);
                throw new IOException(ex);
            }
        } else {
            boolean isUsingStream = true;
            Object o = resource.getRequest().getAttribute(PROPERTY_USE_STREAM);
            if (o != null) {
                isUsingStream = (Boolean)o;
            }

            if (!isUsingStream) {
                try {
                    r.getWriter();
                } catch (IllegalStateException e) {
                    isUsingStream = true;
                }
            }

            if (message instanceof List) {
                Iterator<Object> i = ((List)message).iterator();
                try {
                    Object s;
                    while (i.hasNext()) {
                        s = i.next();
                        if (String.class.isAssignableFrom(s.getClass())) {
                            if (isUsingStream) {
                                r.getOutputStream().write(s.toString().getBytes(r.getCharacterEncoding()));
                            } else {
                                r.getWriter().write(s.toString());
                            }
                        } else if (byte[].class.isAssignableFrom(s.getClass())){
                            if (isUsingStream) {
                                r.getOutputStream().write((byte[])s);
                            } else {
                                r.getWriter().write(s.toString());
                            }
                        } else {
                            if (isUsingStream) {
                                r.getOutputStream().write(s.toString().getBytes(r.getCharacterEncoding()));
                            } else {
                                r.getWriter().write(s.toString());
                            }
                        }
                        i.remove();
                    }
                } catch (IOException ex) {
                    event.setMessage(new ArrayList<String>().addAll((List)message));
                    throw ex;
                }

                if (isUsingStream) {
                   r.getOutputStream().flush();
                } else {
                   r.getWriter().flush();
                }
            } else {
                if (isUsingStream) {
                   r.getOutputStream().write(message.toString().getBytes(r.getCharacterEncoding()));
                   r.getOutputStream().flush();
                } else {
                   r.getWriter().write(message.toString());
                   r.getWriter().flush();
                }
            }
        }
        postStateChange(event);
    }

    /**
     * Inspect the event and decide if the underlying connection must be resumed.
     * @param event
     */
    protected final void postStateChange(AtmosphereResourceEvent event) {
        if (event.isResuming() || event.isCancelled()) return;

        AtmosphereResourceImpl r = AtmosphereResourceImpl.class.cast(event.getResource());
        // Between event.isCancelled and resource, the connection has been remotly closed.
        if (r == null) {
            logger.trace("Event {} returned a null AtmosphereResource", event);
            return;
        }
        Boolean resumeOnBroadcast = r.resumeOnBroadcast();
        if (!resumeOnBroadcast) {
            // For legacy reason, check the attribute as well
            Object o = r.getRequest(false).getAttribute(ApplicationConfig.RESUME_ON_BROADCAST);
            if (o != null && Boolean.class.isAssignableFrom(o.getClass())) {
                resumeOnBroadcast = Boolean.class.cast(o);
            }
        }

        if (resumeOnBroadcast != null && resumeOnBroadcast) {
            r.resume();
        }
    }

    @Override
    public void destroy() {}
}