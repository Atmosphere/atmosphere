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
package org.atmosphere.runtime;

import java.util.Collection;

/**
 * The AtmosphereResourceSession is managed by the
 * {@link AtmosphereResourceSessionFactory}. It is created on demand by via
 * {@link AtmosphereResourceSessionFactory#getSession(AtmosphereResource, boolean)}
 * or {@link AtmosphereResourceSessionFactory#getSession(AtmosphereResource)}.
 * It allows serverside values to be stored against an
 * {@link AtmosphereResource} and lives for the lifetime of the
 * {@link AtmosphereResource}
 *
 * @author uklance (https://github.com/uklance)
 */
public interface AtmosphereResourceSession {
    /**
     * Binds an object to this session, using the name specified
     *
     * @param name  Attribute name
     * @param value Attribute value
     * @return the previous value associated with <tt>name</tt>, or
     * <tt>null</tt> if there was no mapping for <tt>name</tt>
     * @throws IllegalStateException if this method is called on an invalidated session
     */
    Object setAttribute(String name, Object value);

    /**
     * Returns the object bound with the specified name in this session, or null
     * if no object is bound under the name
     *
     * @param name Attribute name
     * @return the object with the specified name
     * @throws IllegalStateException if this method is called on an invalidated session
     */
    Object getAttribute(String name);

    /**
     * Returns the object bound with the specified name in this session, or null
     * if no object is bound under the name
     *
     * @param name Attribute name
     * @param type Attribute type
     * @return the object with the specified name
     * @throws IllegalStateException if this method is called on an invalidated session
     */
    <T> T getAttribute(String name, Class<T> type);

    /**
     * Returns a Collection of Strings containing the names of all the objects
     * bound to this session.
     *
     * @return a Collection of Strings containing the names of all the objects
     * bound to this session
     * @throws IllegalStateException if this method is called on an invalidated session
     */
    Collection<String> getAttributeNames();

    /**
     * Invalidates this session then unbinds any objects bound to it.
     *
     * @throws IllegalStateException if this method is called on an invalidated session
     */
    void invalidate();
}
