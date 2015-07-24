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
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.util.ThreadLocalInvoker;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;

/**
 * {@link }AtmosphereResource} injection implementation support.
 *
 * @author Jeanfrancois Arcand
 */
public class AtmosphereRequestIntrospector extends InjectIntrospectorAdapter<AtmosphereRequest> {

    public WHEN when() {
        return WHEN.RUNTIME;
    }

    @Override
    public boolean supportedType(Type t) {
        return (t instanceof Class) && AtmosphereRequest.class.isAssignableFrom((Class) t);
    }

    @Override
    public AtmosphereRequest injectable(AtmosphereConfig config) {

        final AtmosphereResource r = (AtmosphereResource) config.properties().get(Thread.currentThread().getName() + AtmosphereResource.class.getSimpleName());
        if (r == null) {
            return null;
        }
        final AtmosphereRequest request = r.getRequest();

        return (AtmosphereRequest) Proxy.newProxyInstance(this.getClass().getClassLoader(),
                new Class[]{AtmosphereRequest.class}, new ThreadLocalInvoker() {
                    {
                        set(request);
                    }

                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        return method.invoke(request, args);
                    }
                });
    }

}
