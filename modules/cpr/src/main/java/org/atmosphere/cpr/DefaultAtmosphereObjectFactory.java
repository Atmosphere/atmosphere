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

/**
 * Default {@link AtmosphereObjectFactory} implementation that creates instances
 * using the default no-arg constructor via reflection.
 */
public class DefaultAtmosphereObjectFactory implements AtmosphereObjectFactory<Object> {
    public String toString() {
        return "DefaultAtmosphereObjectFactory";
    }

    @Override
    public void configure(AtmosphereConfig config) {
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T, U extends T> U newClassInstance(Class<T> classType,
                                               Class<U> defaultType) throws InstantiationException, IllegalAccessException {
        try {
            return defaultType.getDeclaredConstructor().newInstance();
        } catch (java.lang.reflect.InvocationTargetException | NoSuchMethodException e) {
            throw new InstantiationException(e.getMessage());
        }
    }

    @Override
    public AtmosphereObjectFactory<Object> allowInjectionOf(java.lang.Object o) {
        return this;
    }
}
