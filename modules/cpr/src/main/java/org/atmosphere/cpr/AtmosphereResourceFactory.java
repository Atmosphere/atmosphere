/*
 * Copyright 2011 Jeanfrancois Arcand
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

import java.io.IOException;
import java.util.Collection;

/**
 * A Factory used to manage {@link AtmosphereResource} instance. You can use that factory to create, remove and find
 * {@link AtmosphereResource} instance that are associated with one or several {@link Broadcaster}.
 *
 * @author Jeanfrancois Arcand
 */
public final class AtmosphereResourceFactory {

    private final static AtmosphereHandler voidAtmosphereHandler = new AbstractReflectorAtmosphereHandler() {
        @Override
        public void onRequest(AtmosphereResource resource) throws IOException {
        }
    };

    /**
     * Create an {@link AtmosphereResourceImpl}
     *
     * @param config      an {@link AtmosphereConfig}
     * @param broadcaster a {@link Broadcaster}
     * @param response    an {@link AtmosphereResponse}
     * @param a           {@link AsyncSupport}
     * @param handler     an {@link AtmosphereHandler}
     * @return an {@link AtmosphereResourceImpl}
     */
    public final static AtmosphereResource create(AtmosphereConfig config,
                                                  Broadcaster broadcaster,
                                                  AtmosphereResponse response,
                                                  AsyncSupport<?> a,
                                                  AtmosphereHandler handler) {
        return new AtmosphereResourceImpl(config, broadcaster, response.request(), response, a, handler);
    }

    /**
     * Create an {@link AtmosphereResourceImpl}
     *
     * @param config   an {@link AtmosphereConfig}
     * @param response an {@link AtmosphereResponse}
     * @param a        {@link AsyncSupport}
     * @return an {@link AtmosphereResourceImpl}
     */
    public final static AtmosphereResource create(AtmosphereConfig config,
                                                  AtmosphereResponse response,
                                                  AsyncSupport<?> a) {
        return new AtmosphereResourceImpl(config, null, response.request(), response, a, voidAtmosphereHandler);
    }

    /**
     * Remove the {@link AtmosphereResource} from all instance of {@link Broadcaster}
     *
     * @param uuid the {@link org.atmosphere.cpr.AtmosphereResource#uuid()}
     * @return the {@link AtmosphereResource}, or null if not found.
     */
    public final static AtmosphereResource remove(String uuid) {
        AtmosphereResource r = find(uuid);
        if (r != null) {
            BroadcasterFactory.getDefault().removeAllAtmosphereResource(r);
        }
        return r;
    }

    /**
     * Find an {@link AtmosphereResource} based on its {@link org.atmosphere.cpr.AtmosphereResource#uuid()}
     *
     * @param uuid the {@link org.atmosphere.cpr.AtmosphereResource#uuid()}
     * @return the {@link AtmosphereResource}, or null if not found.
     */
    public final static AtmosphereResource find(String uuid) {
        Collection<Broadcaster> l = BroadcasterFactory.getDefault().lookupAll();
        for (Broadcaster b : l) {
            for (AtmosphereResource r : b.getAtmosphereResources()) {
                if (r.uuid().equalsIgnoreCase(uuid)) {
                    return r;
                }
            }
        }
        return null;
    }
}
