/*
 * Copyright 2012 Jeanfrancois Arcand
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
package org.atmosphere.guice;

import org.atmosphere.di.Injector;
import org.atmosphere.di.ServletContextHolder;

/**
 * @author Mathieu Carbou
 * @since 0.7
 */
public final class GuiceInjector implements Injector {
    @Override
    public void inject(Object o) {
        com.google.inject.Injector injector = (com.google.inject.Injector)
                ServletContextHolder.getServletContext().getAttribute(com.google.inject.Injector.class.getName());
        if (injector == null)
            throw new IllegalStateException("No Guice Injector found in current ServletContext !");
        injector.injectMembers(o);
    }
}
