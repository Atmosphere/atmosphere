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

import javax.inject.Inject;
import java.lang.reflect.Type;

/**
 * An Injectable class allow the {@link InjectableObjectFactory} to assign a value to a field annotated with the
 * {@link Inject} annotation.
 *
 * @param <T> the Object to inject.
 * @author Jeanfrancois Arcand
 */
public interface Injectable<T> {

    /**
     * Return true if this class support injection of this type.
     * @param t the field
     * @return true if this class support injection
     */
    boolean supportedType(Type t);

    /**
     * Returns an instance of the T
     * @param config the {@link AtmosphereConfig}
     * @return Return an instance of the T
     */
    T injectable(AtmosphereConfig config);
}
