/*
 * Copyright 2008-2026 Async-IO.org
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

import org.atmosphere.util.Utils;

import java.util.LinkedList;

/**
 * Wrapper around an {@link AtmosphereHandler} and its associated {@link Broadcaster},
 * mapping path, and interceptor chain.
 */
public final class AtmosphereHandlerWrapper {

    public final AtmosphereHandler atmosphereHandler;
    public Broadcaster broadcaster;
    public String mapping;
    public final LinkedList<AtmosphereInterceptor> interceptors = new LinkedList<>();
    public boolean create;
    private boolean needRequestScopedInjection;
    private final boolean wilcardMapping;

    public AtmosphereHandlerWrapper(BroadcasterFactory broadcasterFactory, final AtmosphereHandler atmosphereHandler, String mapping,
                                    final AtmosphereConfig config) {
        this.atmosphereHandler = atmosphereHandler;

        try {
            if (broadcasterFactory != null) {
                this.broadcaster = broadcasterFactory.lookup(mapping, true);
            } else {
                this.mapping = mapping;
            }
        } catch (Exception t) {
            throw new RuntimeException(t);
        }
        wilcardMapping = mapping.contains("{") && mapping.contains("}");
        hookInjection(config);
    }

    void hookInjection(final AtmosphereConfig config) {
        config.startupHook(framework -> needRequestScopedInjection = Utils.requestScopedInjection(config, atmosphereHandler));
    }

    public AtmosphereHandlerWrapper(final AtmosphereHandler atmosphereHandler, Broadcaster broadcaster,
                                    final AtmosphereConfig config) {
        this.atmosphereHandler = atmosphereHandler;
        this.broadcaster = broadcaster;
        hookInjection(config);
        wilcardMapping = false;
    }

    @Override
    public String toString() {

        var b = new StringBuilder();
        for (int i = 0; i < interceptors.size(); i++) {
            b.append("\n\t").append(i).append(": ").append(interceptors.get(i).getClass().getName());
        }

        return "\n atmosphereHandler"
                + "\n\t" + atmosphereHandler
                + "\n interceptors" +
                b.toString()
                + "\n broadcaster"
                + "\t" + broadcaster;
    }

    public boolean needRequestScopedInjection() {
        return needRequestScopedInjection;
    }

    public boolean wildcardMapping() {
        return wilcardMapping;
    }
}
