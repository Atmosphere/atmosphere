/*
 * Copyright 2008-2025 Async-IO.org
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

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashSet;

/**
 * @author Jeanfrancois Arcand
 */
public interface InjectionListener {

    <T, U extends T> void preFieldInjection(Field field, U instance, Class<T> clazz);

    <T, U extends T> void postFieldInjection(Field field, U instance, Class<T> clazz);

    <T, U extends T> void preMethodInjection(Method method, U instance, Class<T> clazz);

    <T, U extends T> void postMethodInjection(Method method, U instance, Class<T> clazz);

    <T, U extends T> void nullFieldInjectionFor(Field field, U instance, Class<T> clazz);

    void injectionFailed(LinkedHashSet<Object> pushBackInjection);

    <T, U extends T> void fieldInjectionException(Field field, U instance, Class<T> clazz, Exception ex);

    <T, U extends T> void methodInjectionException(Method method, U instance, Class<T> clazz, Exception ex);

}
