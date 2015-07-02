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

import org.atmosphere.inject.annotation.ApplicationScoped;
import org.atmosphere.inject.annotation.RequestScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import java.lang.reflect.Method;

/**
 * PostConstruct implementation support.
 *
 * @author Jeanfrancois Arcand
 */
@ApplicationScoped
@RequestScoped
public class PostConstructIntrospector extends InjectIntrospectorAdapter<PostConstruct> {
    private final Logger logger = LoggerFactory.getLogger(PostConstructIntrospector.class);

    @Override
    public void introspectMethod(Method m, Object instance) {
        if (!m.isAnnotationPresent(PostConstruct.class)) return;

        try {
            m.setAccessible(true);
            m.invoke(instance);
        } catch (Exception e) {
            logger.error("", e);
        } finally {
            m.setAccessible(false);
        }
    }
}
