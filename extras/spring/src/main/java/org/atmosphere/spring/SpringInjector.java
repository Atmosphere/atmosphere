/*
 * Copyright 2011 Jeanfrancois Arcand
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
package org.atmosphere.spring;

import org.atmosphere.di.Injector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.context.support.SpringBeanAutowiringSupport;

/**
 * An injector for Spring Beans.
 * 
 * @author Jason Burgess
 * @since 0.8.2
 */
public class SpringInjector implements Injector {
    private static final Logger LOGGER = LoggerFactory.getLogger(SpringInjector.class);

    /*
     * (non-Javadoc)
     * 
     * @see org.atmosphere.di.Injector#inject(java.lang.Object)
     */
    @Override
    public void inject(final Object o) {
        LOGGER.trace("inject({})", o);
        SpringBeanAutowiringSupport.processInjectionBasedOnCurrentContext(o);
    }
}
