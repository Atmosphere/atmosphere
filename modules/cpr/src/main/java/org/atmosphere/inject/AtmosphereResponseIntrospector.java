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
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.inject.annotation.RequestScoped;

import java.lang.reflect.Proxy;
import java.lang.reflect.Type;

/**
 * {@link AtmosphereResponse} injection implementation support.
 * <p>
 * One managed service instance serves every client of its path, and each
 * callback re-injects its own response into the shared field just before it
 * runs. While a callback runs, the injected proxy resolves the response of the
 * client whose callback runs on the calling thread, so concurrent callbacks
 * never read each other's client. A reference kept beyond the callback that
 * injected it, or used outside every callback, acts on the response it was
 * injected for. See {@link CallbackScope}.
 *
 * @author Jeanfrancois Arcand
 */
@RequestScoped
public class AtmosphereResponseIntrospector extends InjectIntrospectorAdapter<AtmosphereResponse>
        implements CallbackScoped {

    @Override
    public boolean supportedType(Type t) {
        return (t instanceof Class<?> c) && AtmosphereResponse.class.isAssignableFrom(c);
    }

    @Override
    public AtmosphereResponse injectable(AtmosphereResource r) {
        return (AtmosphereResponse) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{AtmosphereResponse.class}, CallbackScope.inject(r, CallbackScope.Binding::response));
    }
}
