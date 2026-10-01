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
package org.atmosphere.ai.intent;

/**
 * Supplies an endpoint's {@link IntentRouting} from
 * {@code @AiEndpoint(intentRouting = MyRoutes.class)}. The class is instantiated
 * once, when the endpoint registers, through the framework's object factory (so
 * a Spring or CDI bean factory can inject it); {@link #intentRouting()} is read
 * once at that time.
 *
 * <p>The interface itself is the annotation's "no routing" default.</p>
 */
@FunctionalInterface
public interface IntentRoutingProvider {

    /** The routing to install on the endpoint; {@code null} installs none. */
    IntentRouting intentRouting();
}
