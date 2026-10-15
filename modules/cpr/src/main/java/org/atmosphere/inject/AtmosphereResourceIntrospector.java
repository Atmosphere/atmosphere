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
package org.atmosphere.inject;

import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.inject.annotation.RequestScoped;

import java.lang.reflect.Proxy;
import java.lang.reflect.Type;

/**
 * {@link AtmosphereResource} injection implementation support.
 * <p>
 * One managed service instance serves every client of its path, and each
 * callback re-injects its own resource into the shared field just before it
 * runs. While a callback runs, the injected proxy resolves the resource of the
 * client whose callback runs on the calling thread, so concurrent callbacks
 * never read each other's client. Outside a callback the proxy acts on the
 * resource it was injected for. To keep a client's resource beyond its
 * callback (a map of connected users), store {@link CallbackScope#unwrap}
 * of the field, not the field's value. See {@link CallbackScope}.
 *
 * @author Jeanfrancois Arcand
 */
@RequestScoped
public class AtmosphereResourceIntrospector extends InjectIntrospectorAdapter<AtmosphereResource> {

    @Override
    public boolean supportedType(Type t) {
        return (t instanceof Class<?> c) && AtmosphereResource.class.isAssignableFrom(c);
    }

    @Override
    public AtmosphereResource injectable(AtmosphereResource r) {
        return (AtmosphereResource) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{AtmosphereResource.class}, CallbackScope.inject(r, CallbackScope.Binding::resource));
    }
}
