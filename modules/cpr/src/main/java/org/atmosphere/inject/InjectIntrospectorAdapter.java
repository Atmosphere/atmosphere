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
package org.atmosphere.inject;

import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereResource;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Type;

/**
 * Adapter class for {@link InjectIntrospector}
 *
 * @param <T>
 * @author Jeanfrancois Arcand
 */
public class InjectIntrospectorAdapter<T> implements InjectIntrospector {

    @Override
    public boolean supportedType(Type t) {
        return false;
    }

    @Override
    public void introspectField(Class clazz, Field f) {
    }

    @Override
    public void introspectMethod(Method m, Object instance) {
    }

    @Override
    public Object injectable(AtmosphereResource resource) {
        return null;
    }

    @Override
    public T injectable(AtmosphereConfig config) {
        return null;
    }

}
