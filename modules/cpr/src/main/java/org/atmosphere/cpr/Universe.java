/*
* Copyright 2008-2020 Async-IO.org
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
 * Universe contains static reference to Atmosphere's Factories.
 * </p>
 * PLEASE DO NOT USE THIS CLASS IF YOUR APPLICATION CONTAINS WEBFRAGMENTS OR
 * MORE THAN ONCE ATMOSPHERE SERVLET DEFINED AS THIS CLASS IS USING STATIC
 * INSTANCE.
 * </p>
 * <p/>
 * This is ugly, only here to save your buts, Atmosphere users!
 *
 * @author Jeanfrancois Arcand
 */
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
     * Set the must be unique {@link DefaultMetaBroadcaster}
     *
     * @param a {@link DefaultMetaBroadcaster}
     */
    public static void metaBroadcaster(DefaultMetaBroadcaster a) {
        if (metaBroadcaster != null) {
            metaBroadcasterDuplicate = true;
        }
        metaBroadcaster = a;
    }

    /**
     * Set the must be unique {@link org.atmosphere.cpr.BroadcasterFactory}
     *
     * @param a {@link org.atmosphere.cpr.BroadcasterFactory} Throw exception if Universe methods are used when they are not reliable:modules/runtime/src/main/java/org/atmosphere/runtime/Universe.java
     */
    public static void broadcasterFactory(BroadcasterFactory a) {
        if (factory != null) {
            factoryDuplicate = true;
        }
        factory = a;
    }

    /**
     * Set the must be unique {@link org.atmosphere.cpr.AtmosphereFramework}
     *
     * @param a {@link org.atmosphere.cpr.AtmosphereFramework}hrow exception if Universe methods are used when they are not reliable:modules/runtime/src/main/java/org/atmosphere/runtime/Universe.java
     */
    public static void framework(AtmosphereFramework a) {
        if (framework != null) {
            frameworkDuplicate = true;
        }
        framework = a;
    }

    /**
     * Set the must be unique {@link AtmosphereResourceFactory}
     *
     * @param a {@link AtmosphereResourceFactory}
     */
    public static void resourceFactory(AtmosphereResourceFactory a) {
        if (resourceFactory != null) {
            resourceFactoryDuplicate = true;
        }
        resourceFactory = a;
    }

    /**
     * <<<<<<< HEAD:modules/cpr/src/main/java/org/atmosphere/cpr/Universe.java
     * Set the must be unique {@link org.atmosphere.cpr.AtmosphereResourceSessionFactory}
     *
     * @param a {@link org.atmosphere.cpr.AtmosphereResourceSessionFactory} Throw exception if Universe methods are used when they are not reliable:modules/runtime/src/main/java/org/atmosphere/runtime/Universe.java
     */
    public static void sessionResourceFactory(
            AtmosphereResourceSessionFactory a) {
        if (sessionFactory != null) {
            sessionFactoryDuplicate = true;
        }
        sessionFactory = a;
    }

    /**
     * Return the {@link org.atmosphere.cpr.BroadcasterFactory}
     *
     * @return the {@link org.atmosphere.cpr.BroadcasterFactory}
     */
    public static BroadcasterFactory broadcasterFactory() {
        if (factoryDuplicate) {
            throw new IllegalStateException(
                    "More than one instance has been stored. Universe cannot be used.");
        }
        return factory;
    }

    /**
     * Return the {@link org.atmosphere.cpr.AtmosphereFramework}
     *
     * @return the {@link org.atmosphere.cpr.AtmosphereFramework}
     */
    public static AtmosphereFramework framework() {
        if (frameworkDuplicate) {
            throw new IllegalStateException(
                    "More than one instance has been stored. Universe cannot be used.");
        }
        return framework;
    }

    /**
     * Return the {@link AtmosphereResourceFactory}
     *
     * @return the {@link AtmosphereResourceFactory}
     */
    public static AtmosphereResourceFactory resourceFactory() {
        if (resourceFactoryDuplicate) {
            throw new IllegalStateException(
                    "More than one instance has been stored. Universe cannot be used.");
        }
        return resourceFactory;
    }

    /**
     * <<<<<<< HEAD:modules/cpr/src/main/java/org/atmosphere/cpr/Universe.java
     * Return the {@link org.atmosphere.cpr.AtmosphereResourceSessionFactory}
     * Throw exception if Universe methods are used when they are not reliable:modules/runtime/src/main/java/org/atmosphere/runtime/Universe.java
     */
    public static AtmosphereResourceSessionFactory sessionFactory() {
        if (sessionFactoryDuplicate) {
            throw new IllegalStateException(
                    "More than one instance has been stored. Universe cannot be used.");
        }
        return sessionFactory;
    }

    /**
     * Return the {@link DefaultMetaBroadcaster}
     *
     * @return the {@link DefaultMetaBroadcaster}
     */
    public static DefaultMetaBroadcaster metaBroadcaster() {
        if (metaBroadcasterDuplicate) {
            throw new IllegalStateException(
                    "More than one instance has been stored. Universe cannot be used.");
        }
        return metaBroadcaster;
    }
}
