/*
 * Copyright 2017 Async-IO.org
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
package org.atmosphere.annotation;

import org.atmosphere.config.AtmosphereAnnotation;
import org.atmosphere.config.service.AtmosphereInterceptorService;
import org.atmosphere.runtime.AtmosphereConfig;
import org.atmosphere.runtime.AtmosphereFramework;
import org.atmosphere.runtime.AtmosphereInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@AtmosphereAnnotation(AtmosphereInterceptorService.class)
public class AtmosphereInterceptorServiceProcessor implements Processor<AtmosphereInterceptor> {

    private static final Logger logger = LoggerFactory.getLogger(AtmosphereInterceptorServiceProcessor.class);

    @Override
    public void handle(final AtmosphereFramework framework, Class<AtmosphereInterceptor> annotatedClass) {
        try {
            final AtmosphereInterceptor a = (AtmosphereInterceptor) framework.newClassInstance(AtmosphereInterceptor.class, annotatedClass);
            framework.getAtmosphereConfig().startupHook(new AtmosphereConfig.StartupHook() {
                @Override
                public void started(AtmosphereFramework framework) {
                    framework.interceptor(a);
                }
            });
        } catch (Throwable e) {
            logger.warn("", e);
        }
    }
}
