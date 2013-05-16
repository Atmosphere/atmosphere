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
package org.atmosphere.config.managed;

import org.atmosphere.config.service.Delete;
import org.atmosphere.config.service.Disconnect;
import org.atmosphere.config.service.Get;
import org.atmosphere.config.service.Message;
import org.atmosphere.config.service.Post;
import org.atmosphere.config.service.Put;
import org.atmosphere.config.service.Ready;
import org.atmosphere.config.service.Resume;
import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceEventListenerAdapter;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.handler.AbstractReflectorAtmosphereHandler;
import org.atmosphere.handler.AnnotatedProxy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * An internal implementation of {@link AtmosphereHandler} that implement supports for Atmosphere 1.1 annotation.
 *
 * @author Jeanfrancois
 */
public class ManagedAtmosphereHandler extends AbstractReflectorAtmosphereHandler implements AnnotatedProxy {

    private Logger logger = LoggerFactory.getLogger(ManagedAtmosphereHandler.class);

    private final Object object;
    private final Method onMessageMethod;
    private final Method onDisconnectMethod;
    private final Method onTimeoutMethod;
    private final Method onGetMethod;
    private final Method onPostMethod;
    private final Method onPutMethod;
    private final Method onDeleteMethod;
    private final Method onReadyMethod;
    private final Method onResumeMethod;

    final List<Encoder<?, ?>> encoders = new CopyOnWriteArrayList<Encoder<?, ?>>();
    final List<Decoder<?, ?>> decoders = new CopyOnWriteArrayList<Decoder<?, ?>>();

    public ManagedAtmosphereHandler(Object c) {
        this.object = c;
        this.onMessageMethod = populate(c, Message.class);
        this.onDisconnectMethod = populate(c, Disconnect.class);
        this.onTimeoutMethod = populate(c, Resume.class);
        this.onGetMethod = populate(c, Get.class);
        this.onPostMethod = populate(c, Post.class);
        this.onPutMethod = populate(c, Put.class);
        this.onDeleteMethod = populate(c, Delete.class);
        this.onReadyMethod = populate(c, Ready.class);
        this.onResumeMethod = populate(c, Resume.class);

        if (onMessageMethod != null) {
            populateEncoders(onMessageMethod.getAnnotation(Message.class).encoders());
            populateDecoder(onMessageMethod.getAnnotation(Message.class).decoders());
        }
    }

    @Override
    public void onRequest(final AtmosphereResource resource) throws IOException {
        AtmosphereRequest request = resource.getRequest();
        String method = request.getMethod();

        if (onReadyMethod != null) {
            resource.addEventListener(new AtmosphereResourceEventListenerAdapter() {
                @Override
                public void onSuspend(AtmosphereResourceEvent event) {
                    processReady(event.getResource());
                    event.getResource().removeEventListener(this);
                }
            });
        }

        if (onResumeMethod != null) {
            resource.addEventListener(new AtmosphereResourceEventListenerAdapter() {
                @Override
                public void onResume(AtmosphereResourceEvent event) {
                    invoke(onResumeMethod, event.getResource());
                    resource.removeEventListener(this);
                }
            });
        }

        if (method.equalsIgnoreCase("get")) {
            invoke(onGetMethod, resource);
        } else if (method.equalsIgnoreCase("post")) {
            invoke(onPostMethod, resource);
        } else if (method.equalsIgnoreCase("delete")) {
            invoke(onDeleteMethod, resource);
        } else if (method.equalsIgnoreCase("put")) {
            invoke(onPutMethod, resource);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public void onStateChange(AtmosphereResourceEvent event) throws IOException {

        // Original Value
        AtmosphereResourceImpl r = AtmosphereResourceImpl.class.cast(event.getResource());
        Boolean resumeOnBroadcast = r.resumeOnBroadcast();
        if (!resumeOnBroadcast) {
            // For legacy reason, check the attribute as well
            Object o = r.getRequest(false).getAttribute(ApplicationConfig.RESUME_ON_BROADCAST);
            if (o != null && Boolean.class.isAssignableFrom(o.getClass())) {
                resumeOnBroadcast = Boolean.class.cast(o);
            }
        }

        // Disable resume so cached message can be send in one chunk.
        if (resumeOnBroadcast) {
            r.resumeOnBroadcast(false);
            r.getRequest(false).setAttribute(ApplicationConfig.RESUME_ON_BROADCAST, false);
        }

        AtmosphereResource resource = event.getResource();
        if (event.isCancelled()) {
            invoke(onDisconnectMethod, resource);
        } else if (event.isResumedOnTimeout() || event.isResuming()) {
            invoke(onTimeoutMethod, resource);
        } else {
            Object m = event.getMessage();
            invoke(event, m);
        }

        if (resumeOnBroadcast && r.isSuspended()) {
            r.resume();
        }
    }

    private void invoke(AtmosphereResourceEvent event, Object message) throws IOException {
        Object m = message(onMessageMethod, message);
        if (m != null) {

            if (byte[].class.isAssignableFrom(m.getClass())) {
                m = new String((byte[])m, event.getResource().getResponse().getCharacterEncoding());
            }

            super.onStateChange(event.setMessage(m));
        } else if (onMessageMethod == null) {
            super.onStateChange(event);
        }
    }

    private Method populate(Object c, Class<? extends Annotation> annotation) {
        for (Method m : c.getClass().getMethods()) {
            if (m.isAnnotationPresent(annotation)) {
                return m;
            }
        }
        return null;
    }

    private void populateEncoders(Class<? extends Encoder>[] encoder) {
        for (Class<? extends Encoder> s : encoder) {
            try {
                encoders.add(s.newInstance());
            } catch (Exception e) {
                logger.error("Unable to load encoder {}", s);
            }
        }
    }

    private void populateDecoder(Class<? extends Decoder>[] decoder) {
        for (Class<? extends Decoder> s : decoder) {
            try {
                decoders.add(s.newInstance());
            } catch (Exception e) {
                logger.error("Unable to load encoder {}", s);
            }
        }
    }

    protected Class<?> loadClass(String className) throws Exception {
        try {
            return Thread.currentThread().getContextClassLoader().loadClass(className);
        } catch (Throwable t) {
            return getClass().getClassLoader().loadClass(className);
        }
    }

    private Object invoke(Method m, Object o) {
        if (m != null) {
            try {
                return m.invoke(object, o == null ? new Object[]{} : new Object[]{o});
            } catch (IllegalAccessException e) {
                logger.debug("", e);
            } catch (InvocationTargetException e) {
                logger.debug("", e);
            }
        }
        return null;
    }

    private Object message(Method m, Object o) {
        if (m != null) {
            return Invoker.invokeMethod(encoders, decoders, o, object, m);
        }
        return null;
    }

    @Override
    public Object target() {
        return object;
    }

    protected void processReady(AtmosphereResource r) {
        Object o = message(onReadyMethod, r);

        switch (onReadyMethod.getAnnotation(Ready.class).value()) {
            case RESOURCE:
                if (o != null) {
                    if (String.class.isAssignableFrom(o.getClass())) {
                        r.write(o.toString());
                    } else if (byte[].class.isAssignableFrom(o.getClass())) {
                        r.write((byte[]) o);
                    }
                }
                break;
            case BROADCASTER:
                r.getBroadcaster().broadcast(o);
                break;
            case ALL:
                for (Broadcaster b : r.getAtmosphereConfig().getBroadcasterFactory().lookupAll()) {
                    b.broadcast(o);
                }
                break;

        }
    }
}
