/*
 * Copyright 2008-2021 Async-IO.org
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
package org.atmosphere.samples.chat.custom;

import org.atmosphere.annotation.Processor;
import org.atmosphere.config.AtmosphereAnnotation;
import org.atmosphere.cpr.AtmosphereFramework;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A Simple {@link Processor} that demonstrate how you can extend you application with custom annotation.
 *
 * @author eanfrancois Arcand
 */
@AtmosphereAnnotation(Config.class)
public class SampleConfig implements Processor<Object> {

    private final Logger logger = LoggerFactory.getLogger(SampleConfig.class);

    @Override
    public void handle(AtmosphereFramework framework, Class<Object> annotatedClass) {
        logger.info("Custom annotation {} discovered. Starting the Chat Sample", annotatedClass.getAnnotation(Config.class));
    }
}
