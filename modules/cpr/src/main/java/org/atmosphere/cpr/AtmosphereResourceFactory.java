/*
 * Copyright 2014 Jeanfrancois Arcand
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

/**
 * A Factory used to manage {@link AtmosphereResource} instances. You can use this factory to create, remove and find
 * {@link AtmosphereResource} instances that are associated with one or several {@link Broadcaster}s.
 *
 * @author Jeanfrancois Arcand
 */
public final class AtmosphereResourceFactory {

    private final static Logger logger = LoggerFactory.getLogger(AtmosphereResourceFactory.class);
    private final static AtmosphereResourceFactory factory = new AtmosphereResourceFactory();
    private final static Broadcaster noOps = (Broadcaster)
            Proxy.newProxyInstance(Broadcaster.class.getClassLoader(), new Class[]{Broadcaster.class},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
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

    private final ConcurrentHashMap<String, AtmosphereResource>
            resources = new ConcurrentHashMap<String, AtmosphereResource>();

    /**
     * Create an {@link AtmosphereResourceImpl}
     *
     * @param config  an {@link AtmosphereConfig}
     * @param request an {@link AtmosphereResponse}
     * @param a       {@link AsyncSupport}
     * @return an {@link AtmosphereResourceImpl}
     */
    public final AtmosphereResource create(AtmosphereConfig config,
                                           AtmosphereRequest request,
                                           AtmosphereResponse response,
                                           AsyncSupport<?> a) {
        AtmosphereResource r = null;
        try {
            r = config.framework().newClassInstance(AtmosphereResource.class, AtmosphereResourceImpl.class);
            r.initialize(config, null, request, response, a, voidAtmosphereHandler);
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
    public final AtmosphereResource create(AtmosphereConfig config,
                                           Broadcaster broadcaster,
                                           AtmosphereRequest request,
                                           AtmosphereResponse response,
                                           AsyncSupport<?> a,
                                           AtmosphereHandler handler) {
        AtmosphereResource r = null;
        try {
            r = config.framework().newClassInstance(AtmosphereResource.class, AtmosphereResourceImpl.class);
            r.initialize(config, broadcaster, request, response, a, handler);
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
    public final AtmosphereResource create(AtmosphereConfig config,
                                           Broadcaster broadcaster,
                                           AtmosphereResponse response,
                                           AsyncSupport<?> a,
                                           AtmosphereHandler handler) {
        return create(config, broadcaster, response.request(), response, a, handler);
    }

    /**
     * Create an {@link AtmosphereResourceImpl}.
     *
     * @param config   an {@link AtmosphereConfig}
     * @param response an {@link AtmosphereResponse}
     * @param a        {@link AsyncSupport}
     * @return an {@link AtmosphereResourceImpl}
     */
    public final AtmosphereResource create(AtmosphereConfig config,
                                           AtmosphereResponse response,
                                           AsyncSupport<?> a) {
        AtmosphereResource r = null;
        try {
            r = config.framework().newClassInstance(AtmosphereResource.class, AtmosphereResourceImpl.class);
            r.initialize(config, null, response.request(), response, a, voidAtmosphereHandler);
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
    public final AtmosphereResource create(AtmosphereConfig config, String uuid) {
        AtmosphereResponse response = AtmosphereResponse.newInstance();
        response.setHeader(HeaderConfig.X_ATMOSPHERE_TRACKING_ID, uuid);
        return create(config,
                noOps,
                AtmosphereRequest.newInstance(),
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
    public final AtmosphereResource remove(String uuid) {
        logger.trace("Removing: {}", uuid);
        AtmosphereResource r = resources.remove(uuid);
        if (r != null) {
            r.getAtmosphereConfig().getBroadcasterFactory().removeAllAtmosphereResource(r);
        }
        return r;
    }

    /**
     * Find an {@link AtmosphereResource} based on its {@link org.atmosphere.cpr.AtmosphereResource#uuid()}.
     *
     * @param uuid the {@link org.atmosphere.cpr.AtmosphereResource#uuid()}
     * @return the {@link AtmosphereResource}, or null if not found.
     */
    public final AtmosphereResource find(String uuid) {
        if (uuid == null) return null;
        return resources.get(uuid);
    }

    /**
     * Return all {@link Broadcaster} associated with a {@link AtmosphereResource#uuid}, e.g for which
     * {@link Broadcaster#addAtmosphereResource(AtmosphereResource)} has been called. Note that this
     * method is not synchronized and may not return all the {@link Broadcaster} in case
     * {@link Broadcaster#addAtmosphereResource(AtmosphereResource)} is being called concurrently.
     *
     * @param uuid the {@link org.atmosphere.cpr.AtmosphereResource#uuid()}
     * @return all {@link Broadcaster} associated with a {@link AtmosphereResource#uuid}
     */
    public final Set<Broadcaster> broadcasters(String uuid) {
        Collection<Broadcaster> l = BroadcasterFactory.getDefault().lookupAll();
        Set<Broadcaster> h = new HashSet<Broadcaster>();
        for (Broadcaster b : l) {
            for (AtmosphereResource r : b.getAtmosphereResources()) {
                if (r.uuid().equalsIgnoreCase(uuid)) {
                    h.add(b);
                }
            }
        }
        return h;
    }

    public final static AtmosphereResourceFactory getDefault() {
        return factory;
    }

    /**
     * Register an {@link AtmosphereResource} for being a candidate to {@link #find(String)} operation.
     *
     * @param r {@link AtmosphereResource}
     */
    public void registerUuidForFindCandidate(AtmosphereResource r) {
        logger.trace("Adding: {}", r);
        resources.put(r.uuid(), r);
    }

    /**
     * Un register an {@link AtmosphereResource} for being a candidate to {@link #find(String)} operation.
     *
     * @param r {@link AtmosphereResource}
     */
    public void unRegisterUuidForFindCandidate(AtmosphereResource r) {
        logger.trace("Removing: {}", r);
        resources.remove(r.uuid());
    }

    public void destroy() {
        resources.clear();
    }
}
