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
package org.atmosphere.aienvironment;

import org.atmosphere.ai.AiEnvironment;
import org.atmosphere.ai.processor.AiHandlerDecorator;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereHandler;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Records, while {@code AiEndpointProcessor.handle} registers the probe
 * endpoint, which {@link AiEnvironment} the framework has: the context's own
 * when the starter attached it before the endpoint scan, a lazily created one
 * otherwise. Returns the handler unchanged.
 */
public final class EnvironmentRecordingDecorator implements AiHandlerDecorator {

    static final Map<AtmosphereFramework, AiEnvironment> SEEN = new ConcurrentHashMap<>();

    @Override
    public AtmosphereHandler decorate(AtmosphereHandler handler, Context context) {
        if ("/atmosphere/ai-environment-probe".equals(context.path()) && context.framework() != null) {
            AiEnvironment.find(context.framework().getAtmosphereConfig())
                    .ifPresent(environment -> SEEN.put(context.framework(), environment));
        }
        return handler;
    }
}
