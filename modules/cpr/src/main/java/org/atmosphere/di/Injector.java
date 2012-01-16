/*
 * Copyright 2012 Jeanfrancois Arcand
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
package org.atmosphere.di;

/**
 * Represent an injector, capable of providing instances or inject resources into objects.
 * Implementations are likely to delegate to some DI frameworks like Google Guice or Spring
 *
 * @author Mathieu Carbou
 * @since 0.7
 */
public interface Injector {

    /**
     * Asks the underlying DI framework to inject resources into this instance. This method can be used when instances
     * are not created by the DI framework but with Atmosphere instead.
     *
     * @param o The instance to inject
     */
    void inject(Object o);
}
