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

import org.atmosphere.inject.AtmosphereConfigAware;

import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ConcurrentMap;

/**
 * A Factory used to manage {@link AtmosphereResource} instances. You can use this factory to create, remove and find
 * {@link AtmosphereResource} instances that are associated with one or several {@link Broadcaster}s.
 *
 * @author Jeanfrancois Arcand
 */
public interface AtmosphereResourceFactory extends AtmosphereConfigAware {
    void configure(AtmosphereConfig config);

    /**
     * Create an {@link AtmosphereResourceImpl}
     *
     * @param config  an {@link AtmosphereConfig}
     * @param request an {@link AtmosphereResponse}
     * @param a       {@link AsyncSupport}
     * @return an {@link AtmosphereResourceImpl}
     */
    AtmosphereResource create(AtmosphereConfig config,
                              AtmosphereRequest request,
                              AtmosphereResponse response,
                              AsyncSupport<?> a);

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
    AtmosphereResource create(AtmosphereConfig config,
                              Broadcaster broadcaster,
                              AtmosphereRequest request,
                              AtmosphereResponse response,
                              AsyncSupport<?> a,
                              AtmosphereHandler handler);

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
    AtmosphereResource create(AtmosphereConfig config,
                              Broadcaster broadcaster,
                              AtmosphereRequest request,
                              AtmosphereResponse response,
                              AsyncSupport<?> a,
                              AtmosphereHandler handler,
                              AtmosphereResource.TRANSPORT t);

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
    AtmosphereResource create(AtmosphereConfig config,
                              Broadcaster broadcaster,
                              AtmosphereResponse response,
                              AsyncSupport<?> a,
                              AtmosphereHandler handler);

    AtmosphereResource create(AtmosphereConfig config,
                              Broadcaster broadcaster,
                              AtmosphereResponse response,
                              AsyncSupport<?> a,
                              AtmosphereHandler handler,
                              AtmosphereResource.TRANSPORT t);

    /**
     * Create an {@link AtmosphereResourceImpl}.
     *
     * @param config   an {@link AtmosphereConfig}
     * @param response an {@link AtmosphereResponse}
     * @param a        {@link AsyncSupport}
     * @return an {@link AtmosphereResourceImpl}
     */
    AtmosphereResource create(AtmosphereConfig config,
                              AtmosphereResponse response,
                              AsyncSupport<?> a);

    /**
     * Create an {@link AtmosphereResource} associated with the uuid.
     *
     * @param config an {@link AtmosphereConfig}
     * @param uuid   a String representing a UUID
     * @return
     */
    AtmosphereResource create(AtmosphereConfig config, String uuid);

    /**
     * Create an {@link AtmosphereResource} associated with the uuid.
     *
     * @param config  an {@link AtmosphereConfig}
     * @param uuid    a String representing a UUID
     * @param request a {@link AtmosphereRequest}
     * @return
     */
    AtmosphereResource create(AtmosphereConfig config, String uuid, AtmosphereRequest request);

    /**
     * Remove the {@link AtmosphereResource} from all instances of {@link Broadcaster}.
     *
     * @param uuid the {@link org.atmosphere.cpr.AtmosphereResource#uuid()}
     * @return the {@link AtmosphereResource}, or null if not found.
     */
    AtmosphereResource remove(String uuid);

    /**
     * Find an {@link AtmosphereResource} based on its {@link org.atmosphere.cpr.AtmosphereResource#uuid()}.
     *
     * @param uuid the {@link org.atmosphere.cpr.AtmosphereResource#uuid()}
     * @return the {@link AtmosphereResource}, or null if not found.
     */
    AtmosphereResource find(String uuid);

    /**
     * Locate an {@link AtmosphereResource}, based on its {@link org.atmosphere.cpr.AtmosphereResource#uuid()}, in a
     * cluster. If the {@link AtmosphereResource} is available in the cluster, the {@link org.atmosphere.cpr.AtmosphereResourceFactory.Async#available}
     * callback will be invoked. If not, the {@link org.atmosphere.cpr.AtmosphereResourceFactory.Async#notAvailable}
     *
     * @param uuid the {@link org.atmosphere.cpr.AtmosphereResource#uuid()}
     * @param async an {@link Async}
     */
    void locate(String uuid, Async async);

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
    @Deprecated
    Set<Broadcaster> broadcasters(String uuid);

    /**
     * Register an {@link AtmosphereResource} for being a candidate to {@link #find(String)} operation.
     *
     * @param r {@link AtmosphereResource}
     */
    void registerUuidForFindCandidate(AtmosphereResource r);

    /**
     * Un register an {@link AtmosphereResource} for being a candidate to {@link #find(String)} operation.
     *
     * @param r {@link AtmosphereResource}
     */
    void unRegisterUuidForFindCandidate(AtmosphereResource r);

    void destroy();

    ConcurrentMap<String, AtmosphereResource> resources();

    Collection<AtmosphereResource> findAll();

    /**
     * An interface to use in order to retrieve an {@link AtmosphereResource} inside a cluster.
     */
    public static interface Async {

        /**
         * A stub representing an {@link AtmosphereResource} located somewhere in a cluster
         * @param r
         */
        void available(AtmosphereResource r);

    }
}
