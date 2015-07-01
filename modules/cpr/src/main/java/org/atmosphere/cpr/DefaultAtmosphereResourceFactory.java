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

import org.atmosphere.handler.AbstractReflectorAtmosphereHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static org.atmosphere.cpr.HeaderConfig.X_ATMOSPHERE_TRANSPORT;

/**
 * A Factory used to manage {@link AtmosphereResource} instances. You can use this factory to create, remove and find
 * {@link AtmosphereResource} instances that are associated with one or several {@link Broadcaster}s.
 *
 * @author Jeanfrancois Arcand
 */
public class DefaultAtmosphereResourceFactory implements AtmosphereResourceFactory {

    private final static Logger logger = LoggerFactory.getLogger(DefaultAtmosphereResourceFactory.class);
    private final static Broadcaster noOps = (Broadcaster)
            Proxy.newProxyInstance(Broadcaster.class.getClassLoader(), new Class[]{Broadcaster.class},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                            if (method.getName().equals("isDestroyed")) return false;
                            return null;
                        }
                    });
    private final static AtmosphereHandler noOpsHandler = (AtmosphereHandler)
            Proxy.newProxyInstance(AtmosphereHandler.class.getClassLoader(), new Class[]{AtmosphereHandler.class},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                            return null;
                        }
                    });
    private final static AtmosphereHandler voidAtmosphereHandler = new AbstractReflectorAtmosphereHandler() {
        @Override
        public void onRequest(AtmosphereResource resource) throws IOException {
        }

        @Override
        public void destroy() {
        }
    };
    private BroadcasterFactory broadcasterFactory;
    private final ConcurrentHashMap<String, AtmosphereResource> resources = new ConcurrentHashMap<String, AtmosphereResource>();

    public DefaultAtmosphereResourceFactory(){
    }

    @Override
    public void configure(AtmosphereConfig config) {
        this.broadcasterFactory = config.getBroadcasterFactory();
    }

    /**
     * Create an {@link AtmosphereResourceImpl}
     *
     * @param config  an {@link AtmosphereConfig}
     * @param request an {@link AtmosphereResponse}
     * @param a       {@link AsyncSupport}
     * @return an {@link AtmosphereResourceImpl}
     */
    @Override
    public AtmosphereResource create(AtmosphereConfig config,
                                           AtmosphereRequest request,
                                           AtmosphereResponse response,
                                           AsyncSupport<?> a) {
        AtmosphereResource r = null;
        try {
            r = config.framework().newClassInstance(AtmosphereResource.class, AtmosphereResourceImpl.class);
            r.initialize(config, null, request, response, a, voidAtmosphereHandler);
            setDefaultSerializer(config, r);
        } catch (Exception e) {
            logger.error("", e);
        }
        return r;
    }

    /**
     * Create an {@link AtmosphereResourceImpl}.
     *
     * @param config      an {@link AtmosphereConfig}
     * @param broadcaster a {@link Broadcaster}
     * @param response    an {@link AtmosphereResponse}
     * @param a           {@link AsyncSupport}
     * @param handler     an {@link AtmosphereHandler}
     * @return an {@link AtmosphereResourceImpl}
     */
    @Override
    public AtmosphereResource create(AtmosphereConfig config,
                                           Broadcaster broadcaster,
                                           AtmosphereRequest request,
                                           AtmosphereResponse response,
                                           AsyncSupport<?> a,
                                           AtmosphereHandler handler) {
        return create(config, broadcaster, request, response, a, handler, AtmosphereResource.TRANSPORT.UNDEFINED);
    }

    /**
     * Create an {@link AtmosphereResourceImpl}.
     *
     * @param config      an {@link AtmosphereConfig}
     * @param broadcaster a {@link Broadcaster}
     * @param response    an {@link AtmosphereResponse}
     * @param a           {@link AsyncSupport}
     * @param handler     an {@link AtmosphereHandler}
     * @param t           an {@link org.atmosphere.cpr.AtmosphereResource.TRANSPORT}
     * @return an {@link AtmosphereResourceImpl}
     */
    @Override
    public AtmosphereResource create(AtmosphereConfig config,
                                           Broadcaster broadcaster,
                                           AtmosphereRequest request,
                                           AtmosphereResponse response,
                                           AsyncSupport<?> a,
                                           AtmosphereHandler handler,
                                           AtmosphereResource.TRANSPORT t) {
        AtmosphereResource r = null;
        try {
            r = config.framework().newClassInstance(AtmosphereResource.class, AtmosphereResourceImpl.class);

            if (request.getHeader(X_ATMOSPHERE_TRANSPORT) == null) {
                request.header(X_ATMOSPHERE_TRANSPORT, t.name());
            }
            r.initialize(config, broadcaster, request, response, a, handler);
            setDefaultSerializer(config, r);
        } catch (Exception e) {
            logger.error("", e);
        }
        return r;
    }

    /**
     * Create an {@link AtmosphereResourceImpl}.
     *
     * @param config      an {@link AtmosphereConfig}
     * @param broadcaster a {@link Broadcaster}
     * @param response    an {@link AtmosphereResponse}
     * @param a           {@link AsyncSupport}
     * @param handler     an {@link AtmosphereHandler}
     * @return an {@link AtmosphereResourceImpl}
     */
    @Override
    public AtmosphereResource create(AtmosphereConfig config,
                                           Broadcaster broadcaster,
                                           AtmosphereResponse response,
                                           AsyncSupport<?> a,
                                           AtmosphereHandler handler) {
        return create(config, broadcaster, response.request(), response, a, handler);
    }

    @Override
    public AtmosphereResource create(AtmosphereConfig config,
                                           Broadcaster broadcaster,
                                           AtmosphereResponse response,
                                           AsyncSupport<?> a,
                                           AtmosphereHandler handler,
                                           AtmosphereResource.TRANSPORT t) {
        return create(config, broadcaster, response.request(), response, a, handler, t);
    }

    /**
     * Create an {@link AtmosphereResourceImpl}.
     *
     * @param config   an {@link AtmosphereConfig}
     * @param response an {@link AtmosphereResponse}
     * @param a        {@link AsyncSupport}
     * @return an {@link AtmosphereResourceImpl}
     */
    @Override
    public AtmosphereResource create(AtmosphereConfig config,
                                           AtmosphereResponse response,
                                           AsyncSupport<?> a) {
        AtmosphereResource r = null;
        try {
            r = config.framework().newClassInstance(AtmosphereResource.class, AtmosphereResourceImpl.class);
            r.initialize(config, null, response.request(), response, a, voidAtmosphereHandler);
            setDefaultSerializer(config, r);
        } catch (Exception e) {
            logger.error("", e);
        }
        return r;
    }

    /**
     * Create an {@link AtmosphereResource} associated with the uuid.
     *
     * @param config an {@link AtmosphereConfig}
     * @param uuid   a String representing a UUID
     * @return
     */
    @Override
    public AtmosphereResource create(AtmosphereConfig config, String uuid) {
        AtmosphereResponse response = AtmosphereResponseImpl.newInstance();
        response.setHeader(HeaderConfig.X_ATMOSPHERE_TRACKING_ID, uuid);
        return create(config,
                noOps,
                AtmosphereRequestImpl.newInstance(),
                response,
                config.framework().getAsyncSupport(),
                noOpsHandler);
    }

    /**
     * Create an {@link AtmosphereResource} associated with the uuid.
     *
     * @param config an {@link AtmosphereConfig}
     * @param uuid   a String representing a UUID
     * @param request a {@link AtmosphereRequest}
     * @return
     */
    @Override
    public AtmosphereResource create(AtmosphereConfig config, String uuid, AtmosphereRequest request) {
        AtmosphereResponse response = AtmosphereResponseImpl.newInstance();
        response.setHeader(HeaderConfig.X_ATMOSPHERE_TRACKING_ID, uuid);
        return create(config,
                noOps,
                request,
                response,
                config.framework().getAsyncSupport(),
                noOpsHandler);
    }

    /**
     * Remove the {@link AtmosphereResource} from all instances of {@link Broadcaster}.
     *
     * @param uuid the {@link org.atmosphere.cpr.AtmosphereResource#uuid()}
     * @return the {@link AtmosphereResource}, or null if not found.
     */
    @Override
    public AtmosphereResource remove(String uuid) {
        logger.trace("Removing: {}", uuid);
        AtmosphereResource r = resources.remove(uuid);
        if (r != null) {
            r.removeFromAllBroadcasters();
        }
        return r;
    }

    /**
     * Find an {@link AtmosphereResource} based on its {@link org.atmosphere.cpr.AtmosphereResource#uuid()}.
     *
     * @param uuid the {@link org.atmosphere.cpr.AtmosphereResource#uuid()}
     * @return the {@link AtmosphereResource}, or null if not found.
     */
    @Override
    public AtmosphereResource find(String uuid) {
        if (uuid == null) return null;
        return resources.get(uuid);
    }

    @Override
    public void locate(String uuid, Async async) {
        AtmosphereResource r = find(uuid);
        if (r != null) {
            async.available(r);
        }
    }

    /**
     * Return all {@link Broadcaster} associated with a {@link AtmosphereResource#uuid}, e.g for which
     * {@link Broadcaster#addAtmosphereResource(AtmosphereResource)} has been called. Note that this
     * method is not synchronized and may not return all the {@link Broadcaster} in case
     * {@link Broadcaster#addAtmosphereResource(AtmosphereResource)} is being called concurrently.
     *
     * @param uuid the {@link org.atmosphere.cpr.AtmosphereResource#uuid()}
     * @return all {@link Broadcaster} associated with a {@link AtmosphereResource#uuid}
     * @deprecated Use {@link org.atmosphere.cpr.AtmosphereResourceFactory#find(String)}.broadcasters() instead
     */
    @Override
    @Deprecated
    public Set<Broadcaster> broadcasters(String uuid) {
        AtmosphereResource r = find(uuid);
        return new HashSet<Broadcaster>(r.broadcasters());
    }

    /**
     * Register an {@link AtmosphereResource} for being a candidate to {@link #find(String)} operation.
     *
     * @param r {@link AtmosphereResource}
     */
    @Override
    public void registerUuidForFindCandidate(AtmosphereResource r) {
        logger.trace("Adding: {}", r);
        resources.put(r.uuid(), r);
    }

    /**
     * Un register an {@link AtmosphereResource} for being a candidate to {@link #find(String)} operation.
     *
     * @param r {@link AtmosphereResource}
     */
    @Override
    public void unRegisterUuidForFindCandidate(AtmosphereResource r) {
        Object o = resources.remove(r.uuid());
        if (o != null) {
            logger.trace("Removing: {}", r);
        }
    }

    @Override
    public void destroy() {
        resources.clear();
    }

    @Override
    public ConcurrentMap<String, AtmosphereResource> resources() {
        return resources;
    }

    @Override
    public Collection<AtmosphereResource> findAll() {
        return resources.values();
    }

    private void setDefaultSerializer(AtmosphereConfig config, AtmosphereResource r) throws Exception {
        Class<Serializer> serializerClass = config.framework().getDefaultSerializerClass();
        if (serializerClass != null) {
            Serializer serializer = config.framework().newClassInstance(Serializer.class, serializerClass);
            r.setSerializer(serializer);
        }
    }
}
