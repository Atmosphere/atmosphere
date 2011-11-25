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
package org.atmosphere.di;

import java.util.NoSuchElementException;
import java.util.ServiceLoader;

/**
 * Retreive the injector to use to inject resources into objects
 *
 * @author Mathieu Carbou
 * @since 0.7
 */
public final class InjectorProvider {

    private InjectorProvider() {
    }

    public static Injector getInjector() {
        return LazyProvider.INJECTOR;
    }

    private static final class LazyProvider {
        private static final Injector INJECTOR;

        static {
            Injector injector = new NoopInjector();
            try {
                injector = ServiceLoader.load(Injector.class).iterator().next();
            } catch (NoSuchElementException e) {
            }
            INJECTOR = injector;
        }
    }
}
