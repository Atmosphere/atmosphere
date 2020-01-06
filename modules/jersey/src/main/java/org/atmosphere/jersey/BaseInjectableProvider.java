/*
 * Copyright 2008-2020 Async-IO.org
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
package org.atmosphere.jersey;

import com.sun.jersey.spi.inject.InjectableProvider;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.FrameworkConfig;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.Context;
import java.lang.reflect.Type;

/**
 * @author Paul Sandoz
 */
abstract public class BaseInjectableProvider implements InjectableProvider<Context, Type> {
    // The current {@link HttpServletRequest{
    @Context
    HttpServletRequest req;

    protected AtmosphereResource getAtmosphereResource(Class injectType, boolean session) {

        try {
            AtmosphereResource r = (AtmosphereResource)
                    req.getAttribute(FrameworkConfig.ATMOSPHERE_RESOURCE);

            return r;
        } catch (IllegalStateException ex) {
            throw new IllegalStateException("An instance of the class " + injectType.getName() + " could not be injected because there is no HTTP request in scope", ex);
        }
    }

}
