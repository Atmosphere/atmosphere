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
package org.atmosphere.cpr;

/**
 * Static reference holder for Atmosphere's core factories.
 *
 * @deprecated This class relies on static state and breaks in applications that contain
 * web-fragments or define more than one Atmosphere servlet. Use the instance-scoped
 * alternatives instead:
 * <ul>
 *   <li>{@link AtmosphereFramework#getBroadcasterFactory()}</li>
 *   <li>{@link AtmosphereFramework#atmosphereFactory()}</li>
 *   <li>{@link AtmosphereFramework#sessionFactory()}</li>
 *   <li>{@link AtmosphereFramework#metaBroadcaster()}</li>
 * </ul>
 * Or equivalently via {@link AtmosphereConfig}:
 * <ul>
 *   <li>{@link AtmosphereConfig#getBroadcasterFactory()}</li>
 *   <li>{@link AtmosphereConfig#resourcesFactory()}</li>
 *   <li>{@link AtmosphereConfig#sessionFactory()}</li>
 *   <li>{@link AtmosphereConfig#metaBroadcaster()}</li>
 * </ul>
 *
 * @author Jeanfrancois Arcand
 */
@Deprecated(forRemoval = true)
public class Universe {

    private static BroadcasterFactory factory;
    private static boolean factoryDuplicate = false;
    private static AtmosphereFramework framework;
    private static boolean frameworkDuplicate = false;
    private static AtmosphereResourceFactory resourceFactory;
    private static boolean resourceFactoryDuplicate = false;
    private static AtmosphereResourceSessionFactory sessionFactory;
    private static boolean sessionFactoryDuplicate = false;
    private static DefaultMetaBroadcaster metaBroadcaster;
    private static boolean metaBroadcasterDuplicate = false;

    /**
     * Set the {@link DefaultMetaBroadcaster}.
     *
     * @param a the meta-broadcaster
     * @deprecated Use {@link AtmosphereFramework#metaBroadcaster()} instead.
     */
    @Deprecated(forRemoval = true)
    public static void metaBroadcaster(DefaultMetaBroadcaster a) {
        if (metaBroadcaster != null) {
            metaBroadcasterDuplicate = true;
        }
        metaBroadcaster = a;
    }

    /**
     * Set the {@link BroadcasterFactory}.
     *
     * @param a the broadcaster factory
     * @deprecated Use {@link AtmosphereFramework#getBroadcasterFactory()} instead.
     */
    @Deprecated(forRemoval = true)
    public static void broadcasterFactory(BroadcasterFactory a) {
        if (factory != null) {
            factoryDuplicate = true;
        }
        factory = a;
    }

    /**
     * Set the {@link AtmosphereFramework}.
     *
     * @param a the framework instance
     * @deprecated Inject the framework directly or retrieve via servlet context.
     */
    @Deprecated(forRemoval = true)
    public static void framework(AtmosphereFramework a) {
        if (framework != null) {
            frameworkDuplicate = true;
        }
        framework = a;
    }

    /**
     * Set the {@link AtmosphereResourceFactory}.
     *
     * @param a the resource factory
     * @deprecated Use {@link AtmosphereFramework#atmosphereFactory()} instead.
     */
    @Deprecated(forRemoval = true)
    public static void resourceFactory(AtmosphereResourceFactory a) {
        if (resourceFactory != null) {
            resourceFactoryDuplicate = true;
        }
        resourceFactory = a;
    }

    /**
     * Set the {@link AtmosphereResourceSessionFactory}.
     *
     * @param a the session factory
     * @deprecated Use {@link AtmosphereFramework#sessionFactory()} instead.
     */
    @Deprecated(forRemoval = true)
    public static void sessionResourceFactory(
            AtmosphereResourceSessionFactory a) {
        if (sessionFactory != null) {
            sessionFactoryDuplicate = true;
        }
        sessionFactory = a;
    }

    /**
     * Return the {@link BroadcasterFactory}.
     *
     * @return the broadcaster factory
     * @deprecated Use {@link AtmosphereFramework#getBroadcasterFactory()} instead.
     */
    @Deprecated(forRemoval = true)
    public static BroadcasterFactory broadcasterFactory() {
        if (factoryDuplicate) {
            throw new IllegalStateException(
                    "More than one instance has been stored. Universe cannot be used.");
        }
        return factory;
    }

    /**
     * Return the {@link AtmosphereFramework}.
     *
     * @return the framework instance
     * @deprecated Inject the framework directly or retrieve via servlet context.
     */
    @Deprecated(forRemoval = true)
    public static AtmosphereFramework framework() {
        if (frameworkDuplicate) {
            throw new IllegalStateException(
                    "More than one instance has been stored. Universe cannot be used.");
        }
        return framework;
    }

    /**
     * Return the {@link AtmosphereResourceFactory}.
     *
     * @return the resource factory
     * @deprecated Use {@link AtmosphereFramework#atmosphereFactory()} instead.
     */
    @Deprecated(forRemoval = true)
    public static AtmosphereResourceFactory resourceFactory() {
        if (resourceFactoryDuplicate) {
            throw new IllegalStateException(
                    "More than one instance has been stored. Universe cannot be used.");
        }
        return resourceFactory;
    }

    /**
     * Return the {@link AtmosphereResourceSessionFactory}.
     *
     * @return the session factory
     * @deprecated Use {@link AtmosphereFramework#sessionFactory()} instead.
     */
    @Deprecated(forRemoval = true)
    public static AtmosphereResourceSessionFactory sessionFactory() {
        if (sessionFactoryDuplicate) {
            throw new IllegalStateException(
                    "More than one instance has been stored. Universe cannot be used.");
        }
        return sessionFactory;
    }

    /**
     * Return the {@link DefaultMetaBroadcaster}.
     *
     * @return the meta-broadcaster
     * @deprecated Use {@link AtmosphereFramework#metaBroadcaster()} instead.
     */
    @Deprecated(forRemoval = true)
    public static DefaultMetaBroadcaster metaBroadcaster() {
        if (metaBroadcasterDuplicate) {
            throw new IllegalStateException(
                    "More than one instance has been stored. Universe cannot be used.");
        }
        return metaBroadcaster;
    }
}
