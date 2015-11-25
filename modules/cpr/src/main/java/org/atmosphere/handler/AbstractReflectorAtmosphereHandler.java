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


package org.atmosphere.handler;

import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.AtmosphereResponseImpl;
import org.atmosphere.cpr.AtmosphereServletProcessor;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.util.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import javax.servlet.ServletOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.atmosphere.cpr.ApplicationConfig.PROPERTY_USE_STREAM;

/**
 * Simple {@link AtmosphereHandler} that reflect every call to
 * {@link Broadcaster#broadcast}, eg sent the broadcasted event back to the remote client. All broadcasts will be by default returned
 * as it is to the suspended {@link AtmosphereResponseImpl#getOutputStream}
 * or {@link AtmosphereResponseImpl#getWriter()}.
 *
 * @author Jean-francois Arcand
 */
public abstract class AbstractReflectorAtmosphereHandler implements AtmosphereServletProcessor {

    private static final Logger logger = LoggerFactory.getLogger(AbstractReflectorAtmosphereHandler.class);

    private boolean twoStepsWrite;

    /**
     * Write the {@link AtmosphereResourceEvent#getMessage()} back to the client using
     * the {@link AtmosphereResponseImpl#getOutputStream()} or {@link AtmosphereResponseImpl#getWriter()}.
     * If a {@link org.atmosphere.cpr.Serializer} is defined, it will be invoked and the write operation
     * will be delegated to it.
     * <p/>
     * By default, this method will try to use {@link AtmosphereResponseImpl#getWriter()}.
     *
     * @param event the {@link AtmosphereResourceEvent#getMessage()}
     * @throws java.io.IOException
     */
    @Override
    public void onStateChange(AtmosphereResourceEvent event)
            throws IOException {

        Object message = event.getMessage();
        AtmosphereResource resource = event.getResource();
        AtmosphereResponse r = resource.getResponse();
        AtmosphereRequest request = resource.getRequest();

        boolean writeAsBytes = IOUtils.isBodyBinary(request);
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
                } else {
                    resource.getSerializer().write(resource.getResponse().getOutputStream(), message);
                }
            } catch (Throwable ex) {
                logger.warn("Serializer exception: message: {}", message, ex);
                throw new IOException(ex);
            }
        } else {
            boolean isUsingStream = true;
            Object o = resource.getRequest().getAttribute(PROPERTY_USE_STREAM);
            if (o != null) {
                isUsingStream = (Boolean) o;
            }

            if (!isUsingStream) {
                try {
                    r.getWriter();
                } catch (IllegalStateException e) {
                    isUsingStream = true;
                }
                if (writeAsBytes) {
                    throw new IllegalStateException("Cannot write bytes using PrintWriter");
                }
            }

            if (message instanceof List) {
                Iterator<Object> i = ((List) message).iterator();
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
                        } else if (byte[].class.isAssignableFrom(s.getClass())) {
                            if (isUsingStream) {
                                r.getOutputStream().write((byte[]) s);
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
                    event.setMessage(new ArrayList<String>().addAll((List) message));
                    throw ex;
                }

                if (isUsingStream) {
                    r.getOutputStream().flush();
                } else {
                    r.getWriter().flush();
                }
            } else {
                if (isUsingStream) {
                    r.getOutputStream().write(writeAsBytes ? (byte[]) message : message.toString().getBytes(r.getCharacterEncoding()));
                    r.getOutputStream().flush();
                } else {
                    r.getWriter().write(message.toString());
                    r.getWriter().flush();
                }
            }
        }
        postStateChange(event);
    }

    protected void write(AtmosphereResourceEvent event, ServletOutputStream o, byte[] data) throws IOException {
        if (useTwoStepWrite(event) && data.length > 1) {
            twoStepWrite(o, data);
        } else {
            o.write(data);
            o.flush();
        }
    }

    /**
     * Writes the given data to the given outputstream in two steps with extra
     * flushes to make servers notice if the connection has been closed. This
     * enables caching the message instead of losing it, if the client is in the
     * progress of reconnecting
     *
     * @param o the stream to write to
     * @param data the data to write
     * @throws IOException if an exception occurs during writing
     */
    private void twoStepWrite(ServletOutputStream o, byte[] data) throws IOException {
        o.write(data, 0, 1);
        o.flush();
        o.write(data, 1, data.length - 1);
        o.flush();
    }

    protected boolean useTwoStepWrite(AtmosphereResourceEvent event) {
        return twoStepsWrite && event.getResource().transport() == AtmosphereResource.TRANSPORT.LONG_POLLING;
    }

    /**
     * Inspect the event and decide if the underlying connection must be resumed.
     *
     * @param event
     */
    protected final void postStateChange(AtmosphereResourceEvent event) {
        if (event.isCancelled() || event.isResuming()) return;

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
    public void destroy() {
    }

    @Override
    public void init(AtmosphereConfig config) throws ServletException {
        twoStepsWrite = config.getInitParameter(ApplicationConfig.TWO_STEPS_WRITE, false);
    }

    /**
     * <p>
     * This default implementation does nothing when {@link #onRequest(org.atmosphere.cpr.AtmosphereResource)} is called.
     * It could be used when all the installed {@link org.atmosphere.cpr.AtmosphereInterceptor interceptors} do the job
     * and the framework requires us to install an handler.
     * </p>
     *
     * @author Guillaume DROUET
     * @version 1.0
     * @since 2.2
     */
    public static final class Default extends AbstractReflectorAtmosphereHandler {

        /**
         * {@inheritDoc}
         */
        @Override
        public void onRequest(final AtmosphereResource resource) throws IOException {
        }
    }
}