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
import org.atmosphere.inject.annotation.RequestScoped;
import org.atmosphere.util.ThreadLocalInvoker;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;

/**
 * {@link }AtmosphereResource} injection implementation support.
 *
 * @author Jeanfrancois Arcand
 */
@RequestScoped
public class AtmosphereResourceIntrospector extends InjectIntrospectorAdapter<AtmosphereResource> {

    @Override
    public boolean supportedType(Type t) {
        return (t instanceof Class) && AtmosphereResource.class.isAssignableFrom((Class) t);
    }

    @Override
    public AtmosphereResource injectable(final AtmosphereResource r) {
        return (AtmosphereResource) Proxy.newProxyInstance(this.getClass().getClassLoader(),
                new Class[]{AtmosphereResource.class}, new ThreadLocalInvoker() {
                    {
                        set(r);
                    }

                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        return method.invoke(r, args);
                    }
                });
    }

}
