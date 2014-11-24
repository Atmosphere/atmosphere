package org.atmosphere.cpr;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Universe contains static reference to Atmosphere's Factories.
 * </p>
 * PLEASE DO NOT USE THIS CLASS IF YOUR APPLICATION CONTAINS WEBFRAGMENTS OR MORE THAN ONCE ATMOSPHERE SERVLET DEFINED
 * AS THIS CLASS IS USING STATIC INSTANCE.
 * </p>
 * <p/>
 * This is ugly, only here to save your buts, Atmosphere users!
 *
 * @author Jeanfrancois Arcand
 */
public class Universe {

    private static final Logger logger = LoggerFactory.getLogger(Universe.class);
    private static BroadcasterFactory factory;
    private static AtmosphereFramework framework;
    private static AtmosphereResourceFactory resourceFactory;
    private static AtmosphereResourceSessionFactory sessionFactory;
    private static DefaultMetaBroadcaster metaBroadcaster;

    /**
     * Set the must be unique {@link DefaultMetaBroadcaster}
     *
     * @param a {@link DefaultMetaBroadcaster}
     */
    public static void metaBroadcaster(DefaultMetaBroadcaster a) {
        if (metaBroadcaster != null) {
            logger.warn("More than one Universe configured. Universe class will gives wrong object reference {}", a);
        }
        metaBroadcaster = a;
    }

    /**
     * Set the must be unique {@link org.atmosphere.cpr.BroadcasterFactory}
     *
     * @param a {@link org.atmosphere.cpr.BroadcasterFactory}
     */
    public static void broadcasterFactory(BroadcasterFactory a) {
        if (factory != null) {
            logger.warn("More than one Universe configured. Universe class will gives wrong object reference {}", a);
        }
        factory = a;
    }

    /**
     * Set the must be unique {@link org.atmosphere.cpr.AtmosphereFramework}
     *
     * @param a {@link org.atmosphere.cpr.AtmosphereFramework}
     */
    public static void framework(AtmosphereFramework a) {
        if (framework != null) {
            logger.warn("More than one Universe configured. Universe class will gives wrong object reference {}", a);
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
            logger.warn("More than one Universe configured. Universe class will gives wrong object reference {}", a);
        }
        resourceFactory = a;
    }

    /**
     * Set the must be unique {@link org.atmosphere.cpr.AtmosphereResourceSessionFactory}
     *
     * @param a {@link org.atmosphere.cpr.AtmosphereResourceSessionFactory}
     */
    public static void sessionResourceFactory(AtmosphereResourceSessionFactory a) {
        if (sessionFactory != null) {
            logger.warn("More than one Universe configured. Universe class will gives wrong object reference {}", a);
        }
        sessionFactory = a;
    }

    /**
     * Return the {@link org.atmosphere.cpr.BroadcasterFactory}
     *
     * @return the {@link org.atmosphere.cpr.BroadcasterFactory}
     */
    public static BroadcasterFactory broadcasterFactory() {
        return factory;
    }

    /**
     * Return the {@link org.atmosphere.cpr.AtmosphereFramework}
     *
     * @return the {@link org.atmosphere.cpr.AtmosphereFramework}
     */
    public static AtmosphereFramework framework() {
        return framework;
    }

    /**
     * Return the {@link AtmosphereResourceFactory}
     *
     * @return the {@link AtmosphereResourceFactory}
     */
    public static AtmosphereResourceFactory resourceFactory() {
        return resourceFactory;
    }

    /**
     * Return the {@link org.atmosphere.cpr.AtmosphereResourceSessionFactory}
     *
     * @return the {@link org.atmosphere.cpr.AtmosphereResourceSessionFactory}
     */
    public static AtmosphereResourceSessionFactory sessionFactory() {
        return sessionFactory;
    }

    /**
     * Return the {@link DefaultMetaBroadcaster}
     *
     * @return the {@link DefaultMetaBroadcaster}
     */
    public static DefaultMetaBroadcaster metaBroadcaster() {
        return metaBroadcaster;
    }
}
