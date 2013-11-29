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
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceEventListenerAdapter;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.handler.AbstractReflectorAtmosphereHandler;
import org.atmosphere.handler.AnnotatedProxy;
import org.atmosphere.util.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * An internal implementation of {@link AtmosphereHandler} that implement support for Atmosphere 2.0 annotations.
 *
 * @author Jeanfrancois
 */
public class ManagedAtmosphereHandler extends AbstractReflectorAtmosphereHandler implements AnnotatedProxy {

    private Logger logger = LoggerFactory.getLogger(ManagedAtmosphereHandler.class);
    private final static List<Decoder<?, ?>> EMPTY = Collections.<Decoder<?, ?>>emptyList();
    private Object proxiedInstance;
    private List<MethodInfo> onRuntimeMethod;
    private Method onDisconnectMethod;
    private Method onTimeoutMethod;
    private Method onGetMethod;
    private Method onPostMethod;
    private Method onPutMethod;
    private Method onDeleteMethod;
    private Method onReadyMethod;
    private Method onResumeMethod;
    private AtmosphereConfig config;

    final Map<Method, List<Encoder<?, ?>>> encoders = new HashMap<Method, List<Encoder<?, ?>>>();
    final Map<Method, List<Decoder<?, ?>>> decoders = new HashMap<Method, List<Decoder<?, ?>>>();

    public ManagedAtmosphereHandler() {
    }

    public ManagedAtmosphereHandler configure(AtmosphereConfig config, Object c) {
        this.proxiedInstance = c;
        this.onRuntimeMethod = populateMessage(c, Message.class);
        this.onDisconnectMethod = populate(c, Disconnect.class);
        this.onTimeoutMethod = populate(c, Resume.class);
        this.onGetMethod = populate(c, Get.class);
        this.onPostMethod = populate(c, Post.class);
        this.onPutMethod = populate(c, Put.class);
        this.onDeleteMethod = populate(c, Delete.class);
        this.onReadyMethod = populate(c, Ready.class);
        this.onResumeMethod = populate(c, Resume.class);
        this.config = config;

        scanForReaderOrInputStream();

        if (onRuntimeMethod.size() > 0) {
            populateEncoders();
            populateDecoders();
        }
        return this;
    }

    @Override
    public void onRequest(final AtmosphereResource resource) throws IOException {
        AtmosphereRequest request = resource.getRequest();
        String method = request.getMethod();
        boolean polling = resource.transport().equals(AtmosphereResource.TRANSPORT.POLLING);

        if (onReadyMethod != null && !polling) {
            resource.addEventListener(new AtmosphereResourceEventListenerAdapter() {
                @Override
                public void onSuspend(AtmosphereResourceEvent event) {
                    processReady(event.getResource());
                    event.getResource().removeEventListener(this);
                }
            });
        }

        if (onResumeMethod != null && !polling) {
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
            String body = null;
            if (onPostMethod != null) {
                body = IOUtils.readEntirely(resource).toString();
                resource.getRequest().body(body);
                invoke(onPostMethod, resource);
            }

            Object o = message(resource, body);
            if (o != null) {
                resource.getBroadcaster().broadcast(o);
            }
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

        if (event.isCancelled() || event.isClosedByClient()) {
            invoke(onDisconnectMethod, event);
        } else if (event.isResumedOnTimeout() || event.isResuming()) {
            invoke(onTimeoutMethod, event);
        } else {
            Object msg = event.getMessage();
            Object o;
            // No method matched. Give a last chance by trying to decode the proxiedInstance.
            // This makes application development more simpler.
            // Chaining of encoder is not supported.
            // TODO: This could be problematic with String + method
            for (MethodInfo m : onRuntimeMethod) {
                o = Invoker.encode(encoders.get(m.method), msg);
                if (o != null) {
                    event.setMessage(o);
                    break;
                }
            }

            super.onStateChange(event);
        }

        if (resumeOnBroadcast && r.isSuspended()) {
            r.resume();
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

    private List<MethodInfo> populateMessage(Object c, Class<? extends Annotation> annotation) {
        ArrayList<MethodInfo> methods = new ArrayList<MethodInfo>();
        for (Method m : c.getClass().getMethods()) {
            if (m.isAnnotationPresent(annotation)) {
                methods.add(new MethodInfo(m));
            }
        }
        return methods;
    }

    private void scanForReaderOrInputStream() {
        for (MethodInfo m : onRuntimeMethod) {
            Class<?>[] classes = m.method.getParameterTypes();
            for (Class<?> c : classes) {
                if (InputStream.class.isAssignableFrom(c)) {
                    m.useStream = true;
                } else if (Reader.class.isAssignableFrom(c)) {
                    m.useReader = true;
                }
            }
        }
    }

    private void populateEncoders() {
        for (MethodInfo m : onRuntimeMethod) {
            List<Encoder<?, ?>> l = new CopyOnWriteArrayList<Encoder<?, ?>>();
            for (Class<? extends Encoder> s : m.method.getAnnotation(Message.class).encoders()) {
                try {
                    l.add(config.framework().newClassInstance(s));
                } catch (Exception e) {
                    logger.error("Unable to load encoder {}", s);
                }
            }
            encoders.put(m.method, l);
        }

        if (onReadyMethod != null) {
            List<Encoder<?, ?>> l = new CopyOnWriteArrayList<Encoder<?, ?>>();
            for (Class<? extends Encoder> s : onReadyMethod.getAnnotation(Ready.class).encoders()) {
                try {
                    l.add(config.framework().newClassInstance(s));
                } catch (Exception e) {
                    logger.error("Unable to load encoder {}", s);
                }
            }
            encoders.put(onReadyMethod, l);
        }
    }

    private void populateDecoders() {
        for (MethodInfo m : onRuntimeMethod) {
            List<Decoder<?, ?>> l = new CopyOnWriteArrayList<Decoder<?, ?>>();
            for (Class<? extends Decoder> s : m.method.getAnnotation(Message.class).decoders()) {
                try {
                    l.add(config.framework().newClassInstance(s));
                } catch (Exception e) {
                    logger.error("Unable to load encoder {}", s);
                }
            }
            decoders.put(m.method, l);
        }
    }

    private Object invoke(Method m, Object o) {
        if (m != null) {
            try {
                return m.invoke(proxiedInstance, o == null ? new Object[]{} : new Object[]{o});
            } catch (IllegalAccessException e) {
                logger.debug("", e);
            } catch (InvocationTargetException e) {
                logger.debug("", e);
            }
        }
        logger.trace("No Method Mapped for {}", o);
        return null;
    }

    private Object message(AtmosphereResource resource, Object o) {
        try {
            for (MethodInfo m : onRuntimeMethod) {

                if (m.useReader) {
                    o = resource.getRequest().getReader();
                } else if (m.useStream) {
                    o = resource.getRequest().getInputStream();
                } else if (o == null) {
                    o = IOUtils.readEntirely(resource).toString();
                    if (String.class.cast(o).isEmpty()) {
                        logger.warn("{} received an empty body", ManagedServiceInterceptor.class.getSimpleName());
                        return null;
                    }
                }

                Object decoded = Invoker.decode(decoders.get(m.method), o);
                if (decoded == null) {
                    decoded = o;
                }
                Object objectToEncode = null;

                if (m.method.getParameterTypes().length > 2) {
                    logger.warn("Injection of more than 2 parameters not supported {}", m);
                }

                if (m.method.getParameterTypes().length == 2) {
                    objectToEncode = Invoker.invokeMethod(m.method, proxiedInstance, resource, decoded);
                } else {
                    objectToEncode = Invoker.invokeMethod(m.method, proxiedInstance, decoded);
                }
                if (objectToEncode != null) {
                    return Invoker.encode(encoders.get(m.method), objectToEncode);
                }
            }
        } catch (Throwable t) {
            logger.error("", t);
        }
        return null;
    }

    private Object message(Method m, Object o) {
        if (m != null) {
            return Invoker.all(encoders.get(m), EMPTY, o, proxiedInstance, m);
        }
        return null;
    }

    @Override
    public Object target() {
        return proxiedInstance;
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

    @Override
    public String toString() {
        return "ManagedAtmosphereHandler proxy for " + proxiedInstance.getClass().getName();
    }

    public final static class MethodInfo {

        final Method method;
        boolean useStream;
        boolean useReader;

        public MethodInfo(Method method) {
            this.method = method;
        }
    }
}
