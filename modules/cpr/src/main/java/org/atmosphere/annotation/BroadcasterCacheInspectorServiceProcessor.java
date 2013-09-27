/*
 * Copyright 2013 Jeanfrancois Arcand
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

import org.atmosphere.cache.BroadcasterCacheInspector;
import org.atmosphere.config.AtmosphereAnnotation;
import org.atmosphere.config.service.BroadcasterCacheInspectorService;
import org.atmosphere.cpr.AtmosphereFramework;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@AtmosphereAnnotation(BroadcasterCacheInspectorService.class)
public class BroadcasterCacheInspectorServiceProcessor implements Processor {

    private static final Logger logger = LoggerFactory.getLogger(BroadcasterCacheInspectorServiceProcessor.class);

    @Override
    public void handle(AtmosphereFramework framework, Class<?> annotatedClass) {
        try {
            framework.addBroadcasterCacheInjector((BroadcasterCacheInspector) framework.newClassInstance(annotatedClass));
        } catch (Throwable e) {
            logger.warn("", e);
        }
    }
}
