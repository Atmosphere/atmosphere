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
package org.atmosphere.annotation;

import org.atmosphere.config.AtmosphereAnnotation;
import org.atmosphere.config.service.AsyncSupportService;
import org.atmosphere.cpr.AsyncSupport;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.DefaultAsyncSupportResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@AtmosphereAnnotation(AsyncSupportService.class)
public class AsyncSupportServiceProcessor implements Processor<AsyncSupport> {

    private static final Logger logger = LoggerFactory.getLogger(AsyncSupportServiceProcessor.class);

    @Override
    public void handle(AtmosphereFramework framework, Class<AsyncSupport> annotatedClass) {
        try {
            framework.setAsyncSupport(new DefaultAsyncSupportResolver(framework.getAtmosphereConfig()).newCometSupport(annotatedClass.getName()));
        } catch (Throwable e) {
            logger.warn("", e);
        }
    }
}
