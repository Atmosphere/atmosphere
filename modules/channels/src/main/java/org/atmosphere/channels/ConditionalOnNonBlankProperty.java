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
package org.atmosphere.channels;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Matches when the named property is set to a non-blank value. A bare
 * {@code @ConditionalOnProperty} also matches an empty value, so an
 * empty-default placeholder such as {@code ${TELEGRAM_BOT_TOKEN:}} would
 * register a channel with a blank credential.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
@Documented
@Conditional(ConditionalOnNonBlankProperty.OnNonBlankPropertyCondition.class)
@interface ConditionalOnNonBlankProperty {

    /** The property that must hold a non-blank value. */
    String value();

    final class OnNonBlankPropertyCondition implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            var attributes = metadata.getAnnotationAttributes(ConditionalOnNonBlankProperty.class.getName());
            if (attributes == null) {
                return false;
            }
            var value = context.getEnvironment().getProperty((String) attributes.get("value"));
            return value != null && !value.isBlank();
        }
    }
}
