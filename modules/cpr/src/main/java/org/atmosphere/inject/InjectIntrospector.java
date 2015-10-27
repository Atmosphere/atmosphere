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

import org.atmosphere.cpr.AtmosphereResource;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * An Injectable class allow the {@link InjectableObjectFactory} to inspect fields and methods before they get injected or executed.
 * This interface supports application scoped {@link @ApplicationScoped} and request scoped {@link @RequestScoped} injection.
 *
 * @param <T> the Object to inject.
 * @author Jeanfrancois Arcand
 */
public interface InjectIntrospector<T> extends Injectable {

    public enum WHEN { DEPLOY, RUNTIME }

    /**
     * Introspect the field
     *
     * @param f the field
     * @param clazz
     */
    void introspectField(Class<T> clazz, Field f);

    /**
     * Introspect the method
     *
     * @param m the method
     * @param instance the object to invoke the method on
     */
    void introspectMethod(Method m, Object instance);

    /**
     * Returns an instance of the T
     * @param resource the {@link AtmosphereResource}
     * @return Return an instance of the T
     */
    T injectable(AtmosphereResource resource);

}
