/*
 * Copyright 2014 Jeanfrancois Arcand
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
package org.atmosphere.cpr;

/**
 * Factory for {@link AtmosphereResourceSession} instances
 *
 * @author uklance (https://github.com/uklance)
 */
public abstract class AtmosphereResourceSessionFactory {
    // TODO: support IOC
    // TO Make this Happend, we gonna need to pass an AtmosphereFramework's instance to the constructor and
    // use the newInstance method, or add support for some sort of @Inject.
    private static final AtmosphereResourceSessionFactory DEFAULT = new DefaultAtmosphereResourceSessionFactory();

    /**
     * Return the default {@link AtmosphereResourceSessionFactory}
     *
     * @return the default {@link AtmosphereResourceSessionFactory}
     */
    public static AtmosphereResourceSessionFactory getDefault() {
        return DEFAULT;
    }

    /**
     * Returns the current session associated with the
     * {@link AtmosphereResource} or, if there is no current session and create
     * is true, returns a new session.
     * <p/>
     * If create is false and the request has no valid HttpSession, this method
     * returns null.
     *
     * @param resource An {@link AtmosphereResource}
     * @param create   true to create a new session if necessary; false to return
     *                 null if there's no current session
     * @return the session associated with this request or null if create is
     * false and the resource has no valid session
     */
    public abstract AtmosphereResourceSession getSession(AtmosphereResource resource, boolean create);

    /**
     * Returns the current session associated with the
     * {@link AtmosphereResource}, or creates one if it does not yet exist.
     *
     * @param resource An {@link AtmosphereResource}
     * @return the current session associated with the
     * {@link AtmosphereResource}, or creates one if it does not yet
     * exist.
     */
    public AtmosphereResourceSession getSession(AtmosphereResource resource) {
        return getSession(resource, true);
    }
}
