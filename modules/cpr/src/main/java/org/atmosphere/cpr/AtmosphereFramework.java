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
package org.atmosphere.cpr;

import org.atmosphere.annotation.Processor;
import org.atmosphere.cache.BroadcasterCacheInspector;
import org.atmosphere.cache.DefaultBroadcasterCache;
import org.atmosphere.cache.UUIDBroadcasterCache;
import org.atmosphere.config.ApplicationConfiguration;
import org.atmosphere.config.AtmosphereHandlerConfig;
import org.atmosphere.config.AtmosphereHandlerProperty;
import org.atmosphere.config.FrameworkConfiguration;
import org.atmosphere.container.BlockingIOCometSupport;
import org.atmosphere.container.Tomcat7BIOSupportWithWebSocket;
import org.atmosphere.container.WebLogicServlet30WithWebSocket;
import org.atmosphere.handler.AbstractReflectorAtmosphereHandler;
import org.atmosphere.handler.ReflectorServletProcessor;
import org.atmosphere.inject.InjectableObjectFactory;
import org.atmosphere.interceptor.AndroidAtmosphereInterceptor;
import org.atmosphere.interceptor.CacheHeadersInterceptor;
import org.atmosphere.interceptor.CorsInterceptor;
import org.atmosphere.interceptor.HeartbeatInterceptor;
import org.atmosphere.interceptor.IdleResourceInterceptor;
import org.atmosphere.interceptor.InvokationOrder;
import org.atmosphere.interceptor.JSONPAtmosphereInterceptor;
import org.atmosphere.interceptor.JavaScriptProtocol;
import org.atmosphere.interceptor.OnDisconnectInterceptor;
import org.atmosphere.interceptor.PaddingAtmosphereInterceptor;
import org.atmosphere.interceptor.SSEAtmosphereInterceptor;
import org.atmosphere.interceptor.WebSocketMessageSuspendInterceptor;
import org.atmosphere.util.AtmosphereConfigReader;
import org.atmosphere.util.DefaultEndpointMapper;
import org.atmosphere.util.DefaultUUIDProvider;
import org.atmosphere.util.EndpointMapper;
import org.atmosphere.util.ExecutorsFactory;
import org.atmosphere.util.IOUtils;
import org.atmosphere.util.IntrospectionUtils;
import org.atmosphere.util.ServletContextFactory;
import org.atmosphere.util.UUIDProvider;
import org.atmosphere.util.Utils;
import org.atmosphere.util.Version;
import org.atmosphere.util.VoidServletConfig;
import org.atmosphere.util.analytics.FocusPoint;
import org.atmosphere.util.analytics.JGoogleAnalyticsTracker;
import org.atmosphere.util.analytics.ModuleDetection;
import org.atmosphere.websocket.DefaultWebSocketFactory;
import org.atmosphere.websocket.DefaultWebSocketProcessor;
import org.atmosphere.websocket.WebSocket;
import org.atmosphere.websocket.WebSocketFactory;
import org.atmosphere.websocket.WebSocketHandler;
import org.atmosphere.websocket.WebSocketProcessor;
import org.atmosphere.websocket.WebSocketProtocol;
import org.atmosphere.websocket.protocol.SimpleHttpProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.Servlet;
import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import java.io.BufferedReader;
import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.atmosphere.cpr.ApplicationConfig.ALLOW_QUERYSTRING_AS_REQUEST;
import static org.atmosphere.cpr.ApplicationConfig.ATMOSPHERE_HANDLER;
import static org.atmosphere.cpr.ApplicationConfig.ATMOSPHERE_HANDLER_MAPPING;
import static org.atmosphere.cpr.ApplicationConfig.ATMOSPHERE_HANDLER_PATH;
import static org.atmosphere.cpr.ApplicationConfig.BROADCASTER_CACHE;
import static org.atmosphere.cpr.ApplicationConfig.BROADCASTER_CLASS;
import static org.atmosphere.cpr.ApplicationConfig.BROADCASTER_FACTORY;
import static org.atmosphere.cpr.ApplicationConfig.BROADCASTER_LIFECYCLE_POLICY;
import static org.atmosphere.cpr.ApplicationConfig.BROADCASTER_SHAREABLE_LISTENERS;
import static org.atmosphere.cpr.ApplicationConfig.BROADCASTER_WAIT_TIME;
import static org.atmosphere.cpr.ApplicationConfig.BROADCAST_FILTER_CLASSES;
import static org.atmosphere.cpr.ApplicationConfig.DISABLE_ONSTATE_EVENT;
import static org.atmosphere.cpr.ApplicationConfig.META_SERVICE_PATH;
import static org.atmosphere.cpr.ApplicationConfig.PROPERTY_ALLOW_SESSION_TIMEOUT_REMOVAL;
import static org.atmosphere.cpr.ApplicationConfig.PROPERTY_ATMOSPHERE_XML;
import static org.atmosphere.cpr.ApplicationConfig.PROPERTY_BLOCKING_COMETSUPPORT;
import static org.atmosphere.cpr.ApplicationConfig.PROPERTY_COMET_SUPPORT;
import static org.atmosphere.cpr.ApplicationConfig.PROPERTY_NATIVE_COMETSUPPORT;
import static org.atmosphere.cpr.ApplicationConfig.PROPERTY_SERVLET_MAPPING;
import static org.atmosphere.cpr.ApplicationConfig.PROPERTY_SESSION_SUPPORT;
import static org.atmosphere.cpr.ApplicationConfig.PROPERTY_THROW_EXCEPTION_ON_CLONED_REQUEST;
import static org.atmosphere.cpr.ApplicationConfig.PROPERTY_USE_STREAM;
import static org.atmosphere.cpr.ApplicationConfig.SUSPENDED_ATMOSPHERE_RESOURCE_UUID;
import static org.atmosphere.cpr.ApplicationConfig.USE_SERVLET_CONTEXT_PARAMETERS;
import static org.atmosphere.cpr.ApplicationConfig.WEBSOCKET_PROCESSOR;
import static org.atmosphere.cpr.ApplicationConfig.WEBSOCKET_PROTOCOL;
import static org.atmosphere.cpr.ApplicationConfig.WEBSOCKET_SUPPORT;
import static org.atmosphere.cpr.Broadcaster.ROOT_MASTER;
import static org.atmosphere.cpr.FrameworkConfig.ATMOSPHERE_CONFIG;
import static org.atmosphere.cpr.FrameworkConfig.CDI_INJECTOR;
import static org.atmosphere.cpr.FrameworkConfig.GUICE_INJECTOR;
import static org.atmosphere.cpr.FrameworkConfig.HAZELCAST_BROADCASTER;
import static org.atmosphere.cpr.FrameworkConfig.INJECT_LIBARY;
import static org.atmosphere.cpr.FrameworkConfig.JERSEY_BROADCASTER;
import static org.atmosphere.cpr.FrameworkConfig.JERSEY_CONTAINER;
import static org.atmosphere.cpr.FrameworkConfig.JGROUPS_BROADCASTER;
import static org.atmosphere.cpr.FrameworkConfig.JMS_BROADCASTER;
import static org.atmosphere.cpr.FrameworkConfig.KAFKA_BROADCASTER;
import static org.atmosphere.cpr.FrameworkConfig.RABBITMQ_BROADCASTER;
import static org.atmosphere.cpr.FrameworkConfig.REDIS_BROADCASTER;
import static org.atmosphere.cpr.FrameworkConfig.RMI_BROADCASTER;
import static org.atmosphere.cpr.FrameworkConfig.SPRING_INJECTOR;
import static org.atmosphere.cpr.FrameworkConfig.THROW_EXCEPTION_ON_CLONED_REQUEST;
import static org.atmosphere.cpr.FrameworkConfig.XMPP_BROADCASTER;
import static org.atmosphere.cpr.HeaderConfig.ATMOSPHERE_POST_BODY;
import static org.atmosphere.cpr.HeaderConfig.X_ATMOSPHERE_TRACKING_ID;
import static org.atmosphere.util.IOUtils.realPath;
import static org.atmosphere.websocket.WebSocket.WEBSOCKET_SUSPEND;

/**
 * The {@link AtmosphereFramework} is the entry point for the framework. This class can be used to from Servlet/filter
 * to dispatch {@link AtmosphereRequest} and {@link AtmosphereResponse}. The framework can also be configured using
 * the setXXX method. The life cycle of this class is
 * <blockquote><pre>
 * AtmosphereFramework f = new AtmosphereFramework();
 * f.init();
 * f.doCometSupport(AtmosphereRequest, AtmosphereResource);
 * f.destroy();
 * </pre></blockquote>
 *
 * @author Jeanfrancois Arcand
 */
public class AtmosphereFramework {

    public static final String DEFAULT_ATMOSPHERE_CONFIG_PATH = "/META-INF/atmosphere.xml";
    public static final String DEFAULT_LIB_PATH = "/WEB-INF/lib/";
    public static final String DEFAULT_HANDLER_PATH = "/WEB-INF/classes/";
    public static final String META_SERVICE = "META-INF/services/";
    public static final String MAPPING_REGEX = "[a-zA-Z0-9-&.*_~=@;\\?]+";

    protected static final Logger logger = LoggerFactory.getLogger(AtmosphereFramework.class);

    protected final List<String> broadcasterFilters = new ArrayList<String>();
    protected final List<AsyncSupportListener> asyncSupportListeners = new ArrayList<AsyncSupportListener>();
    protected final List<AtmosphereResourceListener> atmosphereResourceListeners = new ArrayList<AtmosphereResourceListener>();
    protected final ArrayList<String> possibleComponentsCandidate = new ArrayList<String>();
    protected final HashMap<String, String> initParams = new HashMap<String, String>();
    protected final AtmosphereConfig config;
    protected final AtomicBoolean isCometSupportConfigured = new AtomicBoolean(false);
    protected final boolean isFilter;
    protected final Map<String, AtmosphereHandlerWrapper> atmosphereHandlers = new ConcurrentHashMap<String, AtmosphereHandlerWrapper>();
    protected final ConcurrentLinkedQueue<String> broadcasterTypes = new ConcurrentLinkedQueue<String>();
    protected final ConcurrentLinkedQueue<String> objectFactoryType = new ConcurrentLinkedQueue<String>();
    protected final ConcurrentLinkedQueue<BroadcasterCacheInspector> inspectors = new ConcurrentLinkedQueue<BroadcasterCacheInspector>();

    protected String mappingRegex = MAPPING_REGEX;
    protected boolean useNativeImplementation;
    protected boolean useBlockingImplementation;
    protected boolean useStreamForFlushingComments = true;
    protected boolean useServlet30 = true;
    protected AsyncSupport asyncSupport;
    protected String broadcasterClassName = DefaultBroadcaster.class.getName();
    protected boolean isCometSupportSpecified;
    protected boolean isBroadcasterSpecified;
    protected boolean isSessionSupportSpecified;
    protected boolean isThrowExceptionOnClonedRequestSpecified;
    protected BroadcasterFactory broadcasterFactory;
    protected String broadcasterFactoryClassName;
    protected String broadcasterCacheClassName;
    protected boolean webSocketEnabled = true;
    protected String broadcasterLifeCyclePolicy = "NEVER";
    protected String webSocketProtocolClassName = SimpleHttpProtocol.class.getName();
    protected WebSocketProtocol webSocketProtocol;
    protected String handlersPath = DEFAULT_HANDLER_PATH;
    protected ServletConfig servletConfig;
    protected boolean autoDetectHandlers = true;
    private boolean hasNewWebSocketProtocol;
    protected String atmosphereDotXmlPath = DEFAULT_ATMOSPHERE_CONFIG_PATH;
    protected String metaServicePath = META_SERVICE;
    protected final LinkedList<AtmosphereInterceptor> interceptors = new LinkedList<AtmosphereInterceptor>();
    protected boolean scanDone;
    protected String annotationProcessorClassName = "org.atmosphere.cpr.DefaultAnnotationProcessor";
    protected final List<BroadcasterListener> broadcasterListeners = Collections.synchronizedList(new ArrayList<BroadcasterListener>());
    protected String webSocketProcessorClassName = DefaultWebSocketProcessor.class.getName();
    protected boolean webSocketProtocolInitialized;
    protected EndpointMapper<AtmosphereHandlerWrapper> endpointMapper = new DefaultEndpointMapper<AtmosphereHandlerWrapper>();
    protected String libPath = DEFAULT_LIB_PATH;
    protected boolean isInit;
    protected boolean sharedThreadPools = true;
    protected final List<String> packages = new ArrayList<String>();
    protected final LinkedList<String> annotationPackages = new LinkedList<String>();
    protected boolean allowAllClassesScan = true;
    protected boolean annotationFound;
    protected boolean executeFirstSet;
    protected AtmosphereObjectFactory<?> objectFactory = new DefaultAtmosphereObjectFactory();
    protected final AtomicBoolean isDestroyed = new AtomicBoolean();
    protected boolean externalizeDestroy;
    protected AnnotationProcessor annotationProcessor;
    protected final List<String> excludedInterceptors = new ArrayList<String>();
    protected final LinkedList<BroadcasterCacheListener> broadcasterCacheListeners = new LinkedList<BroadcasterCacheListener>();
    protected final List<BroadcasterConfig.FilterManipulator> filterManipulators = new ArrayList<BroadcasterConfig.FilterManipulator>();
    protected AtmosphereResourceFactory arFactory;
    protected MetaBroadcaster metaBroadcaster;
    protected AtmosphereResourceSessionFactory sessionFactory;
    protected String defaultSerializerClassName;
    protected Class<Serializer> defaultSerializerClass;
    protected final List<AtmosphereFrameworkListener> frameworkListeners = new LinkedList<AtmosphereFrameworkListener>();
    private UUIDProvider uuidProvider = new DefaultUUIDProvider();
    protected Thread shutdownHook;
    public static final List<Class<? extends AtmosphereInterceptor>> DEFAULT_ATMOSPHERE_INTERCEPTORS = new LinkedList() {
        {
            // Add CORS support
            add(CorsInterceptor.class);
            // Default Interceptor
            add(CacheHeadersInterceptor.class);
            // WebKit & IE Padding
            add(PaddingAtmosphereInterceptor.class);
            // Android 2.3.x streaming support
            add(AndroidAtmosphereInterceptor.class);
            // Heartbeat
            add(HeartbeatInterceptor.class);
            // Add SSE support
            add(SSEAtmosphereInterceptor.class);
            // ADD JSONP support
            add(JSONPAtmosphereInterceptor.class);
            // ADD Tracking ID Handshake
            add(JavaScriptProtocol.class);
            // WebSocket and suspend
            add(WebSocketMessageSuspendInterceptor.class);
            // OnDisconnect
            add(OnDisconnectInterceptor.class);
            // Idle connection
            add(IdleResourceInterceptor.class);
        }
    };
    private WebSocketFactory webSocketFactory;
    private IllegalStateException initializationError;

    /**
     * An implementation of {@link AbstractReflectorAtmosphereHandler}.
     */
    public final static AtmosphereHandler REFLECTOR_ATMOSPHEREHANDLER = new AbstractReflectorAtmosphereHandler() {
        @Override
        public void onRequest(AtmosphereResource resource) throws IOException {
            logger.trace("VoidHandler", resource.uuid());
        }

        @Override
        public void destroy() {
            logger.trace("VoidHandler");
        }
    };

    public void setAndConfigureAtmosphereResourceFactory(AtmosphereResourceFactory arFactory) {
        this.arFactory = arFactory;
        this.arFactory.configure(config);
    }

    public static final class AtmosphereHandlerWrapper {

        public final AtmosphereHandler atmosphereHandler;
        public Broadcaster broadcaster;
        public String mapping;
        public final LinkedList<AtmosphereInterceptor> interceptors = new LinkedList<AtmosphereInterceptor>();
        public boolean create;
        private boolean needRequestScopedInjection;
        private final boolean wilcardMapping;

        public AtmosphereHandlerWrapper(BroadcasterFactory broadcasterFactory, final AtmosphereHandler atmosphereHandler, String mapping,
                                        final AtmosphereConfig config) {
            this.atmosphereHandler = atmosphereHandler;

            try {
                if (broadcasterFactory != null) {
                    this.broadcaster = broadcasterFactory.lookup(mapping, true);
                } else {
                    this.mapping = mapping;
                }
            } catch (Exception t) {
                throw new RuntimeException(t);
            }
            wilcardMapping = mapping.contains("{") && mapping.contains("}");
            hookInjection(config);
        }

        void hookInjection(final AtmosphereConfig config) {
            config.startupHook(new AtmosphereConfig.StartupHook() {
                @Override
                public void started(AtmosphereFramework framework) {
                    needRequestScopedInjection = Utils.requestScopedInjection(config, atmosphereHandler);
                }
            });
        }

        public AtmosphereHandlerWrapper(final AtmosphereHandler atmosphereHandler, Broadcaster broadcaster,
                                        final AtmosphereConfig config) {
            this.atmosphereHandler = atmosphereHandler;
            this.broadcaster = broadcaster;
            hookInjection(config);
            wilcardMapping = false;
        }

        @Override
        public String toString() {

            StringBuilder b = new StringBuilder();
            for (int i = 0; i < interceptors.size(); i++) {
                b.append("\n\t").append(i).append(": ").append(interceptors.get(i).getClass().getName());
            }

            return "\n atmosphereHandler"
                    + "\n\t" + atmosphereHandler
                    + "\n interceptors" +
                    b.toString()
                    + "\n broadcaster"
                    + "\t" + broadcaster;
        }

        public boolean needRequestScopedInjection() {
            return needRequestScopedInjection;
        }

        public boolean wildcardMapping() {
            return wilcardMapping;
        }
    }

    /**
     * <p>
     * This enumeration represents all possible actions to specify in a meta service file.
     * </p>
     *
     * @author Guillaume DROUET
     * @version 1.0
     * @since 2.2.0
     */
    public static enum MetaServiceAction {

        /**
         * Install service.
         */
        INSTALL(new InstallMetaServiceProcedure()),

        /**
         * Exclude service.
         */
        EXCLUDE(new ExcludeMetaServiceProcedure());

        /**
         * The procedure to apply.
         */
        private MetaServiceProcedure procedure;

        /**
         * <p>
         * Builds a new instance.
         * </p>
         *
         * @param p the enum procedure
         */
        private MetaServiceAction(final MetaServiceProcedure p) {
            procedure = p;
        }

        /**
         * <p>
         * Applies this action to given class.
         * </p>
         *
         * @param fwk   the framework
         * @param clazz the class
         * @throws Exception if procedure fails
         */
        public void apply(final AtmosphereFramework fwk, final Class<?> clazz) throws Exception {
            procedure.apply(fwk, clazz);

        }

        /**
         * <p>
         * This interface defined a method with a signature like a procedure to process an action.
         * </p>
         *
         * @author Guillaume DROUET
         * @version 1.0
         * @since 2.2.0
         */
        private static interface MetaServiceProcedure {

            /**
             * <p>
             * Processes an action.
             * </p>
             *
             * @param fwk   the framework
             * @param clazz the class to use during processing
             * @throws Exception if procedure fails
             */
            void apply(final AtmosphereFramework fwk, final Class<?> clazz) throws Exception;
        }

        /**
         * <p>
         * Install the classes.
         * </p>
         *
         * @author Guillaume DROUET
         * @version 1.0
         * @since 2.2.0
         */
        private static class InstallMetaServiceProcedure implements MetaServiceProcedure {

            /**
             * {@inheritDoc}
             */
            @Override
            public void apply(final AtmosphereFramework fwk, final Class c) throws Exception {
                if (AtmosphereInterceptor.class.isAssignableFrom(c)) {
                    fwk.interceptor(fwk.newClassInstance(AtmosphereInterceptor.class, c));
                } else if (Broadcaster.class.isAssignableFrom(c)) {
                    fwk.setDefaultBroadcasterClassName(c.getName());
                } else if (BroadcasterListener.class.isAssignableFrom(c)) {
                    fwk.addBroadcasterListener(fwk.newClassInstance(BroadcasterListener.class, c));
                } else if (BroadcasterCache.class.isAssignableFrom(c)) {
                    fwk.setBroadcasterCacheClassName(c.getName());
                } else if (BroadcastFilter.class.isAssignableFrom(c)) {
                    fwk.broadcasterFilters.add(c.getName());
                } else if (BroadcasterCacheInspector.class.isAssignableFrom(c)) {
                    fwk.inspectors.add(fwk.newClassInstance(BroadcasterCacheInspector.class, c));
                } else if (AsyncSupportListener.class.isAssignableFrom(c)) {
                    fwk.asyncSupportListeners.add(fwk.newClassInstance(AsyncSupportListener.class, c));
                } else if (AsyncSupport.class.isAssignableFrom(c)) {
                    fwk.setAsyncSupport(fwk.newClassInstance(AsyncSupport.class, c));
                } else if (BroadcasterCacheListener.class.isAssignableFrom(c)) {
                    fwk.broadcasterCacheListeners.add(fwk.newClassInstance(BroadcasterCacheListener.class, c));
                } else if (BroadcasterConfig.FilterManipulator.class.isAssignableFrom(c)) {
                    fwk.filterManipulators.add(fwk.newClassInstance(BroadcasterConfig.FilterManipulator.class, c));
                } else if (WebSocketProtocol.class.isAssignableFrom(c)) {
                    fwk.webSocketProtocolClassName = c.getName();
                } else if (WebSocketProcessor.class.isAssignableFrom(c)) {
                    fwk.webSocketProcessorClassName = c.getName();
                } else if (AtmosphereResourceFactory.class.isAssignableFrom(c)) {
                    fwk.setAndConfigureAtmosphereResourceFactory(fwk.newClassInstance(AtmosphereResourceFactory.class, c));
                } else if (AtmosphereFrameworkListener.class.isAssignableFrom(c)) {
                    fwk.frameworkListener(fwk.newClassInstance(AtmosphereFrameworkListener.class, c));
                } else if (WebSocketFactory.class.isAssignableFrom(c)) {
                    fwk.webSocketFactory(fwk.newClassInstance(WebSocketFactory.class, c));
                } else if (AtmosphereFramework.class.isAssignableFrom(c)) {
                    // No OPS
                } else if (EndpointMapper.class.isAssignableFrom(c)) {
                   fwk.endPointMapper(fwk.newClassInstance(EndpointMapper.class, c));
                } else {
                    logger.warn("{} is not a framework service that could be installed", c.getName());
                }
            }
        }

        /**
         * <p>
         * Exclude the services.
         * </p>
         *
         * @author Guillaume DROUET
         * @version 1.0
         * @since 2.2.0
         */
        private static class ExcludeMetaServiceProcedure implements MetaServiceProcedure {

            /**
             * {@inheritDoc}
             */
            @Override
            public void apply(final AtmosphereFramework fwk, final Class<?> c) {
                if (AtmosphereInterceptor.class.isAssignableFrom(c)) {
                    fwk.excludeInterceptor(c.getName());
                } else {
                    logger.warn("{} is not a framework service that could be excluded, pull request is welcome ;-)", c.getName());
                }
            }
        }
    }

    public static class DefaultAtmosphereObjectFactory implements AtmosphereObjectFactory<Object> {
        public String toString() {
            return "DefaultAtmosphereObjectFactory";
        }

        @Override
        public void configure(AtmosphereConfig config) {
        }

        @Override
        public <T, U extends T> U newClassInstance(Class<T> classType,
                                                   Class<U> defaultType) throws InstantiationException, IllegalAccessException {
            return defaultType.newInstance();
        }

        @Override
        public AtmosphereObjectFactory allowInjectionOf(java.lang.Object o) {
            return this;
        }
    }

    /**
     * Create an AtmosphereFramework.
     */
    public AtmosphereFramework() {
        this(false, true);
    }

    /**
     * Create an AtmosphereFramework and initialize it via {@link AtmosphereFramework#init(javax.servlet.ServletConfig)}.
     */
    public AtmosphereFramework(ServletConfig sc) throws ServletException {
        this(false, true);
        // TODO: What?
        init(sc);
    }

    /**
     * Create an AtmosphereFramework.
     *
     * @param isFilter true if this instance is used as an {@link AtmosphereFilter}
     */
    public AtmosphereFramework(boolean isFilter, boolean autoDetectHandlers) {
        this.isFilter = isFilter;
        this.autoDetectHandlers = autoDetectHandlers;
        config = newAtmosphereConfig();
    }

    /**
     * Create an instance of {@link org.atmosphere.cpr.AtmosphereConfig}
     */
    protected AtmosphereConfig newAtmosphereConfig() {
        return new AtmosphereConfig(this);
    }

    /**
     * The order of addition is quite important here.
     */
    private void populateBroadcasterType() {
        broadcasterTypes.add(KAFKA_BROADCASTER);
        broadcasterTypes.add(HAZELCAST_BROADCASTER);
        broadcasterTypes.add(XMPP_BROADCASTER);
        broadcasterTypes.add(REDIS_BROADCASTER);
        broadcasterTypes.add(JGROUPS_BROADCASTER);
        broadcasterTypes.add(JMS_BROADCASTER);
        broadcasterTypes.add(RMI_BROADCASTER);
        broadcasterTypes.add(RABBITMQ_BROADCASTER);
    }

    /**
     * The order of addition is quite important here.
     */
    private void populateObjectFactoryType() {
        objectFactoryType.add(CDI_INJECTOR);
        objectFactoryType.add(SPRING_INJECTOR);
        objectFactoryType.add(GUICE_INJECTOR);
    }

    /**
     * Add an {@link AtmosphereHandler} serviced by the {@link Servlet}
     * This API is exposed to allow embedding an Atmosphere application.
     *
     * @param mapping The servlet mapping (servlet path)
     * @param h       implementation of an {@link AtmosphereHandler}
     * @param l       An array of {@link AtmosphereInterceptor}.
     */
    public AtmosphereFramework addAtmosphereHandler(String mapping, AtmosphereHandler h, List<AtmosphereInterceptor> l) {
        if (!mapping.startsWith("/")) {
            mapping = "/" + mapping;
        }
        createWrapperAndConfigureHandler(h, mapping, l);

        if (!isInit) {
            logger.info("Installed AtmosphereHandler {} mapped to context-path: {}", h.getClass().getName(), mapping);
            logger.info("Installed the following AtmosphereInterceptor mapped to AtmosphereHandler {}", h.getClass().getName());
            if ( !l.isEmpty() ) {
                for (AtmosphereInterceptor s : l) {
                    logger.info("\t{} : {}", s.getClass().getName(), s);
                }
            }
        }
        return this;
    }

    /**
     * Add an {@link AtmosphereHandler} serviced by the {@link Servlet}.
     * This API is exposed to allow embedding an Atmosphere application.
     *
     * @param mapping     The servlet mapping (servlet path)
     * @param h           implementation of an {@link AtmosphereHandler}
     * @param broadcaster The {@link Broadcaster} associated with AtmosphereHandler
     * @param l           A list of {@link AtmosphereInterceptor}s
     */
    public AtmosphereFramework addAtmosphereHandler(String mapping, AtmosphereHandler h, Broadcaster broadcaster, List<AtmosphereInterceptor> l) {
        if (!mapping.startsWith("/")) {
            mapping = "/" + mapping;
        }

        createWrapperAndConfigureHandler(h, mapping, l).broadcaster = broadcaster;

        if (!isInit) {
            logger.info("Installed AtmosphereHandler {} mapped to context-path {} and Broadcaster Class {}",
                    new String[]{h.getClass().getName(), mapping, broadcaster.getClass().getName()});
        } else {
            logger.debug("Installed AtmosphereHandler {} mapped to context-path {} and Broadcaster Class {}",
                    new String[]{h.getClass().getName(), mapping, broadcaster.getClass().getName()});
        }

        if (!l.isEmpty()) {
            logger.info("Installed AtmosphereInterceptor {} mapped to AtmosphereHandler {}", l, h.getClass().getName());
        }
        return this;
    }


    /**
     * Add an {@link AtmosphereHandler} serviced by the {@link Servlet}.
     * This API is exposed to allow embedding an Atmosphere application.
     *
     * @param mapping       The servlet mapping (servlet path)
     * @param h             implementation of an {@link AtmosphereHandler}
     * @param broadcasterId The {@link Broadcaster#getID} value.
     * @param l             A list of {@link AtmosphereInterceptor}
     */
    public AtmosphereFramework addAtmosphereHandler(String mapping, AtmosphereHandler h, String broadcasterId, List<AtmosphereInterceptor> l) {
        if (!mapping.startsWith("/")) {
            mapping = "/" + mapping;
        }

        createWrapperAndConfigureHandler(h, mapping, l).broadcaster.setID(broadcasterId);

        logger.info("Installed AtmosphereHandler {} mapped to context-path: {}", h.getClass().getName(), mapping);
        if (!l.isEmpty()) {
            logger.info("Installed AtmosphereInterceptor {} mapped to AtmosphereHandler {}", l, h.getClass().getName());
        }
        return this;
    }

    protected AtmosphereHandlerWrapper createWrapperAndConfigureHandler(AtmosphereHandler h, String mapping, List<AtmosphereInterceptor> l) {
        AtmosphereHandlerWrapper w = new AtmosphereHandlerWrapper(broadcasterFactory, h, mapping, config);
        addMapping(mapping, w);
        addInterceptorToWrapper(w, l);
        initServletProcessor(h);
        return w;
    }

    /**
     * Add an {@link AtmosphereHandler} serviced by the {@link Servlet}
     * This API is exposed to allow embedding an Atmosphere application.
     *
     * @param mapping The servlet mapping (servlet path)
     * @param h       implementation of an {@link AtmosphereHandler}
     */
    public AtmosphereFramework addAtmosphereHandler(String mapping, AtmosphereHandler h) {
        addAtmosphereHandler(mapping, h, Collections.<AtmosphereInterceptor>emptyList());
        return this;
    }

    private AtmosphereFramework addMapping(String path, AtmosphereHandlerWrapper w) {
        atmosphereHandlers.put(normalizePath(path), w);
        return this;
    }

    public String normalizePath(String path) {
        // We are using JAXRS mapping algorithm.
        if (path.contains("*")) {
            path = path.replace("*", mappingRegex);
        }

        if (path.endsWith("/")) {
            path = path + mappingRegex;
        }
        return path;
    }


    /**
     * Add an {@link AtmosphereHandler} serviced by the {@link Servlet}.
     * This API is exposed to allow embedding an Atmosphere application.
     *
     * @param mapping       The servlet mapping (servlet path)
     * @param h             implementation of an {@link AtmosphereHandler}
     * @param broadcasterId The {@link Broadcaster#getID} value
     */
    public AtmosphereFramework addAtmosphereHandler(String mapping, AtmosphereHandler h, String broadcasterId) {
        addAtmosphereHandler(mapping, h, broadcasterId, Collections.<AtmosphereInterceptor>emptyList());
        return this;
    }

    private void initServletProcessor(AtmosphereHandler h) {
        if (!isInit) return;

        try {
            if (h instanceof AtmosphereServletProcessor) {
                ((AtmosphereServletProcessor) h).init(config);
            }
        } catch (ServletException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Add an {@link AtmosphereHandler} serviced by the {@link Servlet}.
     * This API is exposed to allow embedding an Atmosphere application.
     *
     * @param mapping     The servlet mapping (servlet path)
     * @param h           implementation of an {@link AtmosphereHandler}
     * @param broadcaster The {@link Broadcaster} associated with AtmosphereHandler.
     */
    public AtmosphereFramework addAtmosphereHandler(String mapping, AtmosphereHandler h, Broadcaster broadcaster) {
        addAtmosphereHandler(mapping, h, broadcaster, Collections.<AtmosphereInterceptor>emptyList());
        return this;
    }

    /**
     * Remove an {@link AtmosphereHandler}.
     *
     * @param mapping the mapping used when invoking {@link #addAtmosphereHandler(String, AtmosphereHandler)};
     * @return true if removed
     */
    public AtmosphereFramework removeAtmosphereHandler(String mapping) {

        if (mapping.endsWith("/")) {
            mapping += mappingRegex;
        }

        atmosphereHandlers.remove(mapping);
        return this;
    }

    /**
     * Remove all {@link AtmosphereHandler}s.
     */
    public AtmosphereFramework removeAllAtmosphereHandler() {
        atmosphereHandlers.clear();
        return this;
    }

    /**
     * Remove all init parameters.
     */
    public AtmosphereFramework removeAllInitParams() {
        initParams.clear();
        return this;
    }

    /**
     * Add init-param like if they were defined in web.xml
     *
     * @param name  The name
     * @param value The value
     */
    public AtmosphereFramework addInitParameter(String name, String value) {
        initParams.put(name, value);
        return this;
    }

    protected void readSystemProperties() {
        if (System.getProperty(PROPERTY_NATIVE_COMETSUPPORT) != null) {
            useNativeImplementation = Boolean
                    .parseBoolean(System.getProperty(PROPERTY_NATIVE_COMETSUPPORT));
            isCometSupportSpecified = true;
        }

        if (System.getProperty(PROPERTY_BLOCKING_COMETSUPPORT) != null) {
            useBlockingImplementation = Boolean
                    .parseBoolean(System.getProperty(PROPERTY_BLOCKING_COMETSUPPORT));
            isCometSupportSpecified = true;
        }
        atmosphereDotXmlPath = System.getProperty(PROPERTY_ATMOSPHERE_XML, atmosphereDotXmlPath);

        if (System.getProperty(DISABLE_ONSTATE_EVENT) != null) {
            initParams.put(DISABLE_ONSTATE_EVENT, System.getProperty(DISABLE_ONSTATE_EVENT));
        }
    }

    /**
     * Path specific container using their own property.
     */
    public void patchContainer() {
        System.setProperty("org.apache.catalina.STRICT_SERVLET_COMPLIANCE", "false");
    }

    /**
     * Initialize the AtmosphereFramework. Invoke this method after having properly configured this class using the setters.
     */
    public AtmosphereFramework init() {
        try {
            init(servletConfig == null ? new VoidServletConfig(initParams) : servletConfig, false);
        } catch (ServletException e) {
            logger.error("", e);
        }
        return this;
    }

    /**
     * Initialize the AtmosphereFramework using the {@link ServletContext}.
     *
     * @param sc the {@link ServletContext}
     */
    public AtmosphereFramework init(final ServletConfig sc) throws ServletException {
        return init(sc, true);
    }

    /**
     * Prevent Atmosphere from scanning the entire class path.
     */
    protected void preventOOM() {

        String s = config.getInitParameter(ApplicationConfig.SCAN_CLASSPATH);
        if (s != null) {
            allowAllClassesScan = Boolean.parseBoolean(s);
        }

        try {
            Class.forName("org.testng.Assert");
            allowAllClassesScan = false;
        } catch (ClassNotFoundException e) {
        }
    }

    /**
     * Initialize the AtmosphereFramework using the {@link ServletContext}.
     *
     * @param sc the {@link ServletContext}
     */
    public AtmosphereFramework init(final ServletConfig sc, boolean wrap) throws ServletException {
        if (isInit) return this;

        servletConfig(sc, wrap);
        readSystemProperties();
        populateBroadcasterType();
        populateObjectFactoryType();
        loadMetaService();
        onPreInit();

        try {

            ServletContextFactory.getDefault().init(sc.getServletContext());

            preventOOM();
            doInitParams(servletConfig);
            doInitParamsForWebSocket(servletConfig);
            lookupDefaultObjectFactoryType();

            if (logger.isTraceEnabled()) {
                asyncSupportListener(newClassInstance(AsyncSupportListener.class, AsyncSupportListenerAdapter.class));
            }

            configureObjectFactory();
            configureAnnotationPackages();

            configureBroadcasterFactory();
            configureMetaBroadcaster();
            configureAtmosphereResourceFactory();
            if (isSessionSupportSpecified) {
                sessionFactory();
            }
            configureScanningPackage(servletConfig, ApplicationConfig.ANNOTATION_PACKAGE);
            configureScanningPackage(servletConfig, FrameworkConfig.JERSEY2_SCANNING_PACKAGE);
            configureScanningPackage(servletConfig, FrameworkConfig.JERSEY_SCANNING_PACKAGE);
            // Force scanning of the packages defined.
            defaultPackagesToScan();

            installAnnotationProcessor(servletConfig);

            autoConfigureService(servletConfig.getServletContext());

            // Reconfigure in case an annotation changed the default.
            configureBroadcasterFactory();
            patchContainer();
            configureBroadcaster();
            loadConfiguration(servletConfig);
            initWebSocket();
            initEndpointMapper();
            initDefaultSerializer();

            autoDetectContainer();
            configureWebDotXmlAtmosphereHandler(servletConfig);
            asyncSupport.init(servletConfig);
            initAtmosphereHandler(servletConfig);
            configureAtmosphereInterceptor(servletConfig);
            analytics();

            // http://java.net/jira/browse/ATMOSPHERE-157
            if (sc.getServletContext() != null) {
                sc.getServletContext().setAttribute(BroadcasterFactory.class.getName(), broadcasterFactory);
            }

            String s = config.getInitParameter(ApplicationConfig.BROADCASTER_SHARABLE_THREAD_POOLS);
            if (s != null) {
                sharedThreadPools = Boolean.parseBoolean(s);
            }

            this.shutdownHook = new Thread() {
                public void run() {
                    AtmosphereFramework.this.destroy();
                }
            };

            Runtime.getRuntime().addShutdownHook(this.shutdownHook);

            if (logger.isInfoEnabled()) {
                info();
            }

            if (initializationError != null) {
                logger.trace("ContainerInitalizer exception. May not be an issue if Atmosphere started properly ", initializationError);
            }

            universe();
        } catch (Throwable t) {
            logger.error("Failed to initialize Atmosphere Framework", t);

            if (t instanceof ServletException) {
                throw (ServletException) t;
            }

            throw new ServletException(t);
        }
        isInit = true;
        config.initComplete();

        // wlc 12.x
        if (WebLogicServlet30WithWebSocket.class.isAssignableFrom(asyncSupport.getClass())) {
            servletConfig.getServletContext().setAttribute(AtmosphereConfig.class.getName(), config);
        }

        onPostInit();

        return this;
    }

    protected void servletConfig(final ServletConfig sc, boolean wrap) {
        if (wrap) {

            String value = sc.getServletContext().getInitParameter(USE_SERVLET_CONTEXT_PARAMETERS);
            final boolean useServletContextParameters = value != null && Boolean.valueOf(value);

            servletConfig = new ServletConfig() {

                AtomicBoolean done = new AtomicBoolean();

                public String getServletName() {
                    return sc.getServletName();
                }

                public ServletContext getServletContext() {
                    return sc.getServletContext();
                }

                public String getInitParameter(String name) {
                    String param = initParams.get(name);
                    if (param == null) {
                        param = sc.getInitParameter(name);

                        if (param == null && useServletContextParameters) {
                            param = sc.getServletContext().getInitParameter(name);
                        }
                    }
                    return param;
                }

                public Enumeration<String> getInitParameterNames() {
                    if (!done.getAndSet(true)) {
                        Enumeration en = sc.getInitParameterNames();
                        if (en != null) {
                            while (en.hasMoreElements()) {
                                String name = (String) en.nextElement();
                                if (!initParams.containsKey(name)) {
                                    initParams.put(name, sc.getInitParameter(name));
                                }
                            }
                        }
                    }
                    return Collections.enumeration(initParams.keySet());
                }
            };
        } else {
            servletConfig = sc;
        }
    }

    public void reconfigureInitParams(boolean reconfigureInitParams) {
        if (reconfigureInitParams) {
            doInitParams(servletConfig, reconfigureInitParams);
            doInitParamsForWebSocket(servletConfig);
        }
    }

    private void info() {

        if (logger.isTraceEnabled()) {
            Enumeration<String> e = servletConfig.getInitParameterNames();
            logger.trace("Configured init-params");
            String n;
            while (e.hasMoreElements()) {
                n = e.nextElement();
                logger.trace("\t{} = {}", n, servletConfig.getInitParameter(n));
            }
        }

        logger.info("Using EndpointMapper {}", endpointMapper.getClass());
        for (String i : broadcasterFilters) {
            logger.info("Using BroadcastFilter: {}", i);
        }

        if (broadcasterCacheClassName == null || DefaultBroadcasterCache.class.getName().equals(broadcasterCacheClassName)) {
            logger.warn("No BroadcasterCache configured. Broadcasted message between client reconnection will be LOST. " +
                    "It is recommended to configure the {}", UUIDBroadcasterCache.class.getName());
        } else {
            logger.info("Using BroadcasterCache: {}", broadcasterCacheClassName);
        }

        String s = config.getInitParameter(BROADCASTER_WAIT_TIME);

        logger.info("Default Broadcaster Class: {}", broadcasterClassName);
        logger.info("Broadcaster Shared List Resources: {}", config.getInitParameter(BROADCASTER_SHAREABLE_LISTENERS, false));
        logger.info("Broadcaster Polling Wait Time {}", s == null ? DefaultBroadcaster.POLLING_DEFAULT : s);
        logger.info("Shared ExecutorService supported: {}", sharedThreadPools);

        ExecutorService executorService = ExecutorsFactory.getMessageDispatcher(config, Broadcaster.ROOT_MASTER);
        if (executorService != null) {
            if (ThreadPoolExecutor.class.isAssignableFrom(executorService.getClass())) {
                long max = ThreadPoolExecutor.class.cast(executorService).getMaximumPoolSize();
                logger.info("Messaging Thread Pool Size: {}",
                        ThreadPoolExecutor.class.cast(executorService).getMaximumPoolSize() == 2147483647 ? "Unlimited" : max);
            } else {
                logger.info("Messaging ExecutorService Pool Size unavailable - Not instance of ThreadPoolExecutor");
            }
        }

        executorService = ExecutorsFactory.getAsyncOperationExecutor(config, Broadcaster.ROOT_MASTER);
        if (executorService != null) {
            if (ThreadPoolExecutor.class.isAssignableFrom(executorService.getClass())) {
                logger.info("Async I/O Thread Pool Size: {}",
                        ThreadPoolExecutor.class.cast(executorService).getMaximumPoolSize());
            } else {
                logger.info("Async I/O ExecutorService Pool Size unavailable - Not instance of ThreadPoolExecutor");
            }
        }
        logger.info("Using BroadcasterFactory: {}", broadcasterFactory.getClass().getName());
        logger.info("Using AtmosphereResurceFactory: {}", arFactory.getClass().getName());
        logger.info("Using WebSocketProcessor: {}", webSocketProcessorClassName);
        if (defaultSerializerClassName != null && !defaultSerializerClassName.isEmpty()) {
            logger.info("Using Serializer: {}", defaultSerializerClassName);
        }

        WebSocketProcessor wp = WebSocketProcessorFactory.getDefault().getWebSocketProcessor(this);
        boolean b = false;
        if (DefaultWebSocketProcessor.class.isAssignableFrom(wp.getClass())) {
            b = DefaultWebSocketProcessor.class.cast(wp).invokeInterceptors();
        }
        logger.info("Invoke AtmosphereInterceptor on WebSocket message {}", b);
        logger.info("HttpSession supported: {}", config.isSupportSession());

        logger.info("Atmosphere is using {} for dependency injection and object creation", objectFactory);
        logger.info("Atmosphere is using async support: {} running under container: {}",
                getAsyncSupport().getClass().getName(), asyncSupport.getContainerName());
        logger.info("Atmosphere Framework {} started.", Version.getRawVersion());

        logger.info("\n\n\tFor Atmosphere Framework Commercial Support, visit \n\t{} " +
                "or send an email to {}\n", "http://www.async-io.org/", "support@async-io.org");

        if (logger.isTraceEnabled()) {
            for (Entry<String, AtmosphereHandlerWrapper> e : atmosphereHandlers.entrySet()) {
                logger.trace("\nConfigured AtmosphereHandler {}\n", e.getKey());
                logger.trace("{}", e.getValue());
            }
        }
    }

    protected void universe() {
        Universe.broadcasterFactory(broadcasterFactory);
        Universe.resourceFactory(arFactory);
        Universe.sessionResourceFactory(sessionFactory);
        Universe.framework(this);
    }

    private void configureAnnotationPackages() {
        // We must scan the default annotation set.
        annotationPackages.add(Processor.class.getPackage().getName());

        String s = config.getInitParameter(ApplicationConfig.CUSTOM_ANNOTATION_PACKAGE);
        if (s != null) {
            String[] l = s.split(",");
            for (String p : l) {
                annotationPackages.addLast(p);
            }
        }
    }

    protected void analytics() {
        if (!config.getInitParameter(ApplicationConfig.ANALYTICS, true)) return;

        final String container = getServletContext().getServerInfo();
        Thread t = new Thread() {
            public void run() {
                try {
                    logger.debug("Retrieving Atmosphere's latest version from http://async-io.org/version.html");
                    HttpURLConnection urlConnection = (HttpURLConnection)
                            URI.create("http://async-io.org/version.html").toURL().openConnection();
                    urlConnection.setRequestProperty("User-Agent", "Mozilla/5.0");
                    urlConnection.setRequestProperty("Connection", "keep-alive");
                    urlConnection.setRequestProperty("Cache-Control", "max-age=0");
                    urlConnection.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
                    urlConnection.setRequestProperty("Accept-Language", "en-US,en;q=0.8");
                    urlConnection.setRequestProperty("Accept-Charset", "ISO-8859-1,utf-8;q=0.7,*;q=0.3");
                    urlConnection.setRequestProperty("If-Modified-Since", "ISO-8859-1,utf-8;q=0.7,*;q=0.3");
                    urlConnection.setInstanceFollowRedirects(true);

                    BufferedReader in = new BufferedReader(new InputStreamReader(
                            urlConnection.getInputStream()));

                    String inputLine;
                    String newVersion = Version.getRawVersion();
                    String clientVersion = "2.2.11";
                    String nextMajorRelease = null;
                    boolean nextAvailable = false;
                    if (newVersion.indexOf("SNAPSHOT") == -1) {
                        try {
                            while ((inputLine = in.readLine().trim()) != null) {
                                if (inputLine.startsWith("ATMO23_VERSION=")) {
                                    newVersion = inputLine.substring("ATMO23_VERSION=".length());
                                } else if (inputLine.startsWith("CLIENT3_VERSION=")) {
                                    clientVersion = inputLine.substring("CLIENT3_VERSION=".length());
                                    break;
                                } else if (inputLine.startsWith("ATMO_RELEASE_VERSION=")) {
                                    nextMajorRelease = inputLine.substring("ATMO_RELEASE_VERSION=".length());
                                    if (nextMajorRelease.compareTo(Version.getRawVersion()) > 0
                                            && nextMajorRelease.toLowerCase().indexOf("rc") == -1
                                            && nextMajorRelease.toLowerCase().indexOf("beta") == -1) {
                                        nextAvailable = true;
                                    }
                                }
                            }
                        } finally {
                            logger.info("Latest version of Atmosphere's JavaScript Client {}", clientVersion);
                            if (newVersion.compareTo(Version.getRawVersion()) > 0) {
                                if (nextAvailable) {
                                    logger.info("\n\n\tAtmosphere Framework Updates\n\tMinor available (bugs fixes): {}\n\tMajor available (new features): {}", newVersion, nextMajorRelease);
                                } else {
                                    logger.info("\n\n\tAtmosphere Framework Updates:\n\tMinor Update available (bugs fixes): {}", newVersion);
                                }
                            } else if (nextAvailable) {
                                logger.info("\n\n\tAtmosphere Framework Updates:\n\tMajor Update available (new features): {}", nextMajorRelease);
                            }
                            try {
                                in.close();
                            } catch (IOException ex) {
                            }
                            urlConnection.disconnect();
                        }
                    }

                    JGoogleAnalyticsTracker tracker = new JGoogleAnalyticsTracker(ModuleDetection.detect(), Version.getRawVersion(), "UA-31990725-1");
                    tracker.trackSynchronously(new FocusPoint(container, new FocusPoint("Atmosphere")));

                } catch (Throwable e) {
                }
            }
        };
        t.setDaemon(true);
        t.start();
    }

    /**
     * Configure the list of {@link AtmosphereInterceptor}.
     *
     * @param sc a ServletConfig
     */
    protected void configureAtmosphereInterceptor(ServletConfig sc) {
        String s = sc.getInitParameter(ApplicationConfig.ATMOSPHERE_INTERCEPTORS);
        if (s != null) {
            String[] list = s.split(",");
            for (String a : list) {
                try {
                    AtmosphereInterceptor ai = newClassInstance(AtmosphereInterceptor.class,
                            (Class<AtmosphereInterceptor>) IOUtils
                                    .loadClass(getClass(), a.trim()));
                    interceptor(ai);
                } catch (Exception e) {
                    logger.warn("", e);
                }
            }
        }

        s = sc.getInitParameter(ApplicationConfig.DISABLE_ATMOSPHEREINTERCEPTOR);
        if (s == null || !"true".equalsIgnoreCase(s)) {
            logger.info("Installing Default AtmosphereInterceptors");

            for (Class<? extends AtmosphereInterceptor> a : DEFAULT_ATMOSPHERE_INTERCEPTORS) {
                if (!excludedInterceptors.contains(a.getName())) {
                    interceptors.add(newAInterceptor(a));
                } else {
                    logger.info("Dropping Interceptor {}", a.getName());
                }
            }
            logger.info("Set {} to disable them.", ApplicationConfig.DISABLE_ATMOSPHEREINTERCEPTOR);
        }
        addDefaultOrAppInterceptors();
    }

    protected AtmosphereInterceptor newAInterceptor(Class<? extends AtmosphereInterceptor> a) {
        AtmosphereInterceptor ai = null;
        try {
            ai = newClassInstance(AtmosphereInterceptor.class,
                    (Class<AtmosphereInterceptor>) IOUtils.loadClass(getClass(), a.getName()));
            logger.info("\t{} : {}", a.getName(), ai);
        } catch (Exception ex) {
            logger.warn("", ex);
        }
        return ai;
    }

    private static class InterceptorComparator implements Comparator<AtmosphereInterceptor> {
        @Override
        public int compare(AtmosphereInterceptor i1, AtmosphereInterceptor i2) {
            InvokationOrder.PRIORITY p1, p2;

            if (i1 instanceof InvokationOrder) {
                p1 = ((InvokationOrder) i1).priority();
            } else {
                p1 = InvokationOrder.PRIORITY.AFTER_DEFAULT;
            }

            if (i2 instanceof InvokationOrder) {
                p2 = ((InvokationOrder) i2).priority();
            } else {
                p2 = InvokationOrder.PRIORITY.AFTER_DEFAULT;
            }

            int orderResult = 0;

            switch (p1) {
                case AFTER_DEFAULT:
                    switch (p2) {
                        case BEFORE_DEFAULT:
                        case FIRST_BEFORE_DEFAULT:
                            orderResult = 1;
                            break;
                    }
                    break;

                case BEFORE_DEFAULT:
                    switch (p2) {
                        case AFTER_DEFAULT:
                            orderResult = -1;
                            break;
                        case FIRST_BEFORE_DEFAULT:
                            orderResult = 1;
                            break;
                    }
                    break;

                case FIRST_BEFORE_DEFAULT:
                    switch (p2) {
                        case AFTER_DEFAULT:
                        case BEFORE_DEFAULT:
                            orderResult = -1;
                            break;
                    }
                    break;
            }

            return orderResult;
        }
    }

    protected void configureWebDotXmlAtmosphereHandler(ServletConfig sc) {
        String s = sc.getInitParameter(ATMOSPHERE_HANDLER);
        if (s != null) {
            try {

                String mapping = sc.getInitParameter(ATMOSPHERE_HANDLER_MAPPING);
                if (mapping == null) {
                    mapping = Broadcaster.ROOT_MASTER;
                }
                addAtmosphereHandler(mapping, newClassInstance(AtmosphereHandler.class,
                        (Class<AtmosphereHandler>) IOUtils.loadClass(getClass(), s)));
            } catch (Exception ex) {
                logger.warn("Unable to load WebSocketHandle instance", ex);
            }
        }
    }

    protected void configureScanningPackage(ServletConfig sc, String value) {
        String packageName = sc.getInitParameter(value);
        if (packageName != null) {
            String[] list = packageName.split(",");
            for (String a : list) {
                packages.add(a);
            }
        }
    }

    protected void defaultPackagesToScan() {
        // Atmosphere HA/Pro
        packages.add("io.async.control");
        packages.add("io.async.satellite");
        packages.add("io.async.postman");
    }

    public void configureBroadcasterFactory() {
        try {
            // Check auto supported one
            if (isBroadcasterSpecified == false) {
                broadcasterClassName = lookupDefaultBroadcasterType(broadcasterClassName);
            }

            if (broadcasterFactoryClassName != null && broadcasterFactory == null) {
                broadcasterFactory = newClassInstance(BroadcasterFactory.class,
                        (Class<BroadcasterFactory>) IOUtils.loadClass(getClass(), broadcasterFactoryClassName));
                Class<? extends Broadcaster> bc =
                        (Class<? extends Broadcaster>) IOUtils.loadClass(getClass(), broadcasterClassName);
                broadcasterFactory.configure(bc, broadcasterLifeCyclePolicy, config);
            }

            if (broadcasterFactory == null) {
                Class<? extends Broadcaster> bc =
                        (Class<? extends Broadcaster>) IOUtils.loadClass(getClass(), broadcasterClassName);
                broadcasterFactory = newClassInstance(BroadcasterFactory.class, DefaultBroadcasterFactory.class);
                broadcasterFactory.configure(bc, broadcasterLifeCyclePolicy, config);
            }

            for (BroadcasterListener b : broadcasterListeners) {
                broadcasterFactory.addBroadcasterListener(b);
            }
        } catch (Exception ex) {
            logger.error("Unable to configure Broadcaster/Factory/Cache", ex);
        }
    }

    protected void configureBroadcaster() {

        try {
            Iterator<Entry<String, AtmosphereHandlerWrapper>> i = atmosphereHandlers.entrySet().iterator();
            AtmosphereHandlerWrapper w;
            Entry<String, AtmosphereHandlerWrapper> e;
            while (i.hasNext()) {
                e = i.next();
                w = e.getValue();

                if (w.broadcaster == null) {
                    w.broadcaster = broadcasterFactory.get(w.mapping);
                } else {
                    if (broadcasterCacheClassName != null
                            && w.broadcaster.getBroadcasterConfig().getBroadcasterCache().getClass().getName().equals(
                            DefaultBroadcasterCache.class.getName())) {
                        BroadcasterCache cache = newClassInstance(BroadcasterCache.class,
                                (Class<BroadcasterCache>) IOUtils.loadClass(getClass(), broadcasterCacheClassName));
                        cache.configure(config);
                        w.broadcaster.getBroadcasterConfig().setBroadcasterCache(cache);
                    }
                }
            }
        } catch (Exception ex) {
            logger.error("Unable to configure Broadcaster/Factory/Cache", ex);
        }
    }

    protected void installAnnotationProcessor(ServletConfig sc) {
        String s = sc.getInitParameter(ApplicationConfig.ANNOTATION_PROCESSOR);
        if (s != null) {
            annotationProcessorClassName = s;
        }
    }

    protected void doInitParamsForWebSocket(ServletConfig sc) {
        String s = sc.getInitParameter(WEBSOCKET_SUPPORT);
        if (s != null) {
            webSocketEnabled = Boolean.parseBoolean(s);
            sessionSupport(false);
        }
        s = sc.getInitParameter(WEBSOCKET_PROTOCOL);
        if (s != null) {
            webSocketProtocolClassName = s;
        }

        s = sc.getInitParameter(WEBSOCKET_PROCESSOR);
        if (s != null) {
            webSocketProcessorClassName = s;
        }

        s = config.getInitParameter(ApplicationConfig.WEBSOCKET_SUPPORT_SERVLET3);
        if (s != null) {
            useServlet30 = Boolean.parseBoolean(s);
        }
    }

    /**
     * Read init params from web.xml and apply them.
     *
     * @param sc {@link ServletConfig}
     */
    protected void doInitParams(ServletConfig sc) {
        doInitParams(sc, false);
    }

    /**
     * Read init params from web.xml and apply them.
     *
     * @param sc {@link ServletConfig}
     */
    protected void doInitParams(ServletConfig sc, boolean reconfigure) {
        String s = sc.getInitParameter(PROPERTY_NATIVE_COMETSUPPORT);
        if (s != null) {
            useNativeImplementation = Boolean.parseBoolean(s);
            if (useNativeImplementation) isCometSupportSpecified = true;
        }
        s = sc.getInitParameter(PROPERTY_BLOCKING_COMETSUPPORT);
        if (s != null) {
            useBlockingImplementation = Boolean.parseBoolean(s);
            if (useBlockingImplementation) isCometSupportSpecified = true;
        }
        s = sc.getInitParameter(PROPERTY_USE_STREAM);
        if (s != null) {
            useStreamForFlushingComments = Boolean.parseBoolean(s);
        }
        s = sc.getInitParameter(PROPERTY_COMET_SUPPORT);
        if (asyncSupport == null && s != null && !reconfigure) {
            asyncSupport = new DefaultAsyncSupportResolver(config).newCometSupport(s);
            isCometSupportSpecified = true;
        }
        s = sc.getInitParameter(BROADCASTER_CLASS);
        if (s != null) {
            broadcasterClassName = s;
            isBroadcasterSpecified = true;
        }
        s = sc.getInitParameter(BROADCASTER_CACHE);
        if (s != null) {
            broadcasterCacheClassName = s;
        }

        s = sc.getInitParameter(PROPERTY_SESSION_SUPPORT);
        if (s == null) {
            s = sc.getServletContext().getInitParameter(PROPERTY_SESSION_SUPPORT);
        }

        if (s != null || SessionSupport.initializationHint) {
            boolean sessionSupport = Boolean.valueOf(s) || SessionSupport.initializationHint;
            config.setSupportSession(sessionSupport);
            if (sessionSupport && (sc.getServletContext().getMajorVersion() < 3 || !SessionSupport.initializationHint)) {
                logger.warn("SessionSupport error. Make sure you also define {} as a listener in web.xml, see https://github.com/Atmosphere/atmosphere/wiki/Enabling-HttpSession-Support", SessionSupport.class.getName());
            }
            isSessionSupportSpecified = true;
        }
        s = sc.getInitParameter(PROPERTY_ALLOW_SESSION_TIMEOUT_REMOVAL);
        if (s != null) {
            config.setSessionTimeoutRemovalAllowed(Boolean.valueOf(s));
        }
        s = sc.getInitParameter(PROPERTY_THROW_EXCEPTION_ON_CLONED_REQUEST);
        if (s != null) {
            config.setThrowExceptionOnCloned(Boolean.valueOf(s));
            isThrowExceptionOnClonedRequestSpecified = true;
        }
        s = sc.getInitParameter(DISABLE_ONSTATE_EVENT);
        if (s != null) {
            initParams.put(DISABLE_ONSTATE_EVENT, s);
        } else {
            initParams.put(DISABLE_ONSTATE_EVENT, "false");
        }
        s = sc.getInitParameter(BROADCAST_FILTER_CLASSES);
        if (s != null) {
            broadcasterFilters.addAll(Arrays.asList(s.split(",")));
            logger.info("Installing BroadcastFilter class(es) {}", s);
        }
        s = sc.getInitParameter(BROADCASTER_LIFECYCLE_POLICY);
        if (s != null) {
            broadcasterLifeCyclePolicy = s;
        }
        s = sc.getInitParameter(BROADCASTER_FACTORY);
        if (s != null) {
            broadcasterFactoryClassName = s;
        }
        s = sc.getInitParameter(ATMOSPHERE_HANDLER_PATH);
        if (s != null) {
            handlersPath = s;
        }
        s = sc.getInitParameter(PROPERTY_ATMOSPHERE_XML);
        if (s != null) {
            atmosphereDotXmlPath = s;
        }
        s = sc.getInitParameter(META_SERVICE_PATH);
        if (s != null) {
            metaServicePath = s;
        }
        s = sc.getInitParameter(ApplicationConfig.HANDLER_MAPPING_REGEX);
        if (s != null) {
            mappingRegex = s;
        }

        s = sc.getInitParameter(FrameworkConfig.JERSEY_SCANNING_PACKAGE);
        if (s != null) {
            packages.add(s);
        }

        s = sc.getInitParameter(ApplicationConfig.DEFAULT_SERIALIZER);
        if (s != null) {
            defaultSerializerClassName = s;
        }

        s = sc.getInitParameter(ApplicationConfig.DISABLE_ATMOSPHEREINTERCEPTORS);
        if (s != null) {
            excludedInterceptors.addAll(Arrays.asList(s.trim().replace(" ", "").split(",")));
        }
    }

    public void loadConfiguration(ServletConfig sc) throws ServletException {

        if (!autoDetectHandlers) return;

        try {
            URL url = sc.getServletContext().getResource(handlersPath);
            URLClassLoader urlC = new URLClassLoader(new URL[]{url},
                    Thread.currentThread().getContextClassLoader());
            loadAtmosphereDotXml(sc.getServletContext().
                    getResourceAsStream(atmosphereDotXmlPath), urlC);

            if (atmosphereHandlers.isEmpty()) {
                autoDetectAtmosphereHandlers(sc.getServletContext(), urlC);

                if (atmosphereHandlers.isEmpty()) {
                    detectSupportedFramework(sc);
                }
            }

            autoDetectWebSocketHandler(sc.getServletContext(), urlC);
        } catch (Throwable t) {
            throw new ServletException(t);
        }
    }

    /**
     * Auto-detect Jersey when no atmosphere.xml file is specified.
     *
     * @param sc {@link ServletConfig}
     * @return true if Jersey classes are detected
     * @throws ClassNotFoundException
     */
    protected boolean detectSupportedFramework(ServletConfig sc) throws Exception {

        String broadcasterClassNameTmp = null;

        boolean isJersey = false;
        try {
            IOUtils.loadClass(getClass(), JERSEY_CONTAINER);
            isJersey = true;

            if (!isBroadcasterSpecified) {
                broadcasterClassNameTmp = lookupDefaultBroadcasterType(JERSEY_BROADCASTER);

                IOUtils.loadClass(getClass(), broadcasterClassNameTmp);
            }
            useStreamForFlushingComments = true;

            StringBuilder packagesInit = new StringBuilder();
            for (String s : packages) {
                packagesInit.append(s).append(",");
            }

            initParams.put(FrameworkConfig.JERSEY_SCANNING_PACKAGE, packagesInit.toString());
        } catch (Throwable t) {
            logger.trace("", t);
            return false;
        }

        logger.debug("Missing META-INF/atmosphere.xml but found the Jersey runtime. Starting Jersey");

        // Atmosphere 1.1 : could add regressions
        // Jersey will itself handle the headers.
        //initParams.put(WRITE_HEADERS, "false");

        ReflectorServletProcessor rsp = newClassInstance(ReflectorServletProcessor.class, ReflectorServletProcessor.class);
        if (broadcasterClassNameTmp != null) broadcasterClassName = broadcasterClassNameTmp;
        configureDetectedFramework(rsp, isJersey);
        sessionSupport(false);
        initParams.put(DISABLE_ONSTATE_EVENT, "true");

        String mapping = sc.getInitParameter(PROPERTY_SERVLET_MAPPING);
        if (mapping == null) {
            mapping = sc.getInitParameter(ATMOSPHERE_HANDLER_MAPPING);
            if (mapping == null) {
                mapping = Broadcaster.ROOT_MASTER;
            }
        }
        Class<? extends Broadcaster> bc = (Class<? extends Broadcaster>) IOUtils.loadClass(getClass(), broadcasterClassName);

        broadcasterFactory.destroy();

        broadcasterFactory = newClassInstance(BroadcasterFactory.class, DefaultBroadcasterFactory.class);
        broadcasterFactory.configure(bc, broadcasterLifeCyclePolicy, config);
        for (BroadcasterListener b : broadcasterListeners) {
            broadcasterFactory.addBroadcasterListener(b);
        }

        Broadcaster b;

        try {
            b = broadcasterFactory.get(bc, mapping);
        } catch (IllegalStateException ex) {
            logger.warn("Two Broadcaster's named {}. Renaming the second one to {}", mapping, sc.getServletName() + mapping);
            b = broadcasterFactory.get(bc, sc.getServletName() + mapping);
        }

        addAtmosphereHandler(mapping, rsp, b);
        return true;
    }

    protected void configureDetectedFramework(ReflectorServletProcessor rsp, boolean isJersey) {
        rsp.setServletClassName(JERSEY_CONTAINER);
    }

    protected String lookupDefaultBroadcasterType(String defaultB) {

        String drop = servletConfig != null ? servletConfig.getInitParameter(ApplicationConfig.AUTODETECT_BROADCASTER) : null;
        if (drop == null || !Boolean.parseBoolean(drop)) {
            for (String b : broadcasterTypes) {
                try {
                    Class.forName(b);
                    logger.info("Detected a Broadcaster {} on the classpath. " +
                            "This broadcaster will be used by default and will override any annotated resources. " +
                            "Set {} to false to change the behavior", b, ApplicationConfig.AUTODETECT_BROADCASTER);
                    isBroadcasterSpecified = true;
                    return b;
                } catch (ClassNotFoundException e) {
                }
            }
        }

        return defaultB;
    }

    protected AtmosphereObjectFactory lookupDefaultObjectFactoryType() {

        if (objectFactory != null && !DefaultAtmosphereObjectFactory.class.getName().equals(objectFactory.getClass()
                .getName())) return objectFactory;

        for (String b : objectFactoryType) {
            try {
                Class<?> c = Class.forName(b);
                objectFactory = (AtmosphereObjectFactory) c.newInstance();
                break;
            } catch (ClassNotFoundException e) {
                logger.trace(e.getMessage() + " not found");
            } catch (Exception e) {
                logger.trace("", e);
            }
        }

        if (objectFactory == null || DefaultAtmosphereObjectFactory.class.getName().equals(objectFactory.getClass()
                .getName())) {
            try {
                IOUtils.loadClass(getClass(), INJECT_LIBARY);
                objectFactory = new InjectableObjectFactory();
            } catch (Exception e) {
                logger.trace("javax.inject.Inject nor installed. Using DefaultAtmosphereObjectFactory");
                objectFactory = new DefaultAtmosphereObjectFactory();
            }
        }

        objectFactory.configure(config);
        return objectFactory;
    }

    public void sessionSupport(boolean sessionSupport) {
        if (!isSessionSupportSpecified) {
            config.setSupportSession(sessionSupport);
        } else if (!config.isSupportSession()) {
            // Don't turn off session support.  Once it's on, leave it on.
            config.setSupportSession(sessionSupport);
        }
    }

    /**
     * Initialize {@link AtmosphereServletProcessor}.
     *
     * @param sc the {@link ServletConfig}
     * @throws javax.servlet.ServletException
     * @Deprecated
     */
    public void initAtmosphereHandler(ServletConfig sc) throws ServletException {
        initAtmosphereHandler();
    }

    public void initAtmosphereHandler() throws ServletException {

        AtmosphereHandler a;
        AtmosphereHandlerWrapper w;
        for (Entry<String, AtmosphereHandlerWrapper> h : atmosphereHandlers.entrySet()) {
            w = h.getValue();
            a = w.atmosphereHandler;
            if (a instanceof AtmosphereServletProcessor) {
                ((AtmosphereServletProcessor) a).init(config);
            }
        }
        checkWebSocketSupportState();
    }

    public void checkWebSocketSupportState(){
        if (atmosphereHandlers.isEmpty() && !SimpleHttpProtocol.class.isAssignableFrom(webSocketProtocol.getClass())) {
            logger.debug("Adding a void AtmosphereHandler mapped to /* to allow WebSocket application only");
            addAtmosphereHandler(Broadcaster.ROOT_MASTER, new AbstractReflectorAtmosphereHandler() {
                @Override
                public void onRequest(AtmosphereResource r) throws IOException {
                    logger.debug("No AtmosphereHandler defined.");
                    if (!r.transport().equals(AtmosphereResource.TRANSPORT.WEBSOCKET)) {
                        WebSocket.notSupported(r.getRequest(), r.getResponse());
                    }
                }

                @Override
                public void destroy() {
                }
            });
        }
    }

    public void initWebSocket() {
        if (webSocketProtocolInitialized) return;

        if (webSocketProtocol == null) {
            try {
                webSocketProtocol = newClassInstance(WebSocketProtocol.class,
                        (Class<WebSocketProtocol>) IOUtils.loadClass(this.getClass(), webSocketProtocolClassName));
                logger.info("Installed WebSocketProtocol {} ", webSocketProtocolClassName);
            } catch (Exception ex) {
                logger.error("Cannot load the WebSocketProtocol {}", getWebSocketProtocolClassName(), ex);
                try {
                    webSocketProtocol = newClassInstance(WebSocketProtocol.class, SimpleHttpProtocol.class);
                } catch (Exception e) {
                }
            }
        }
        webSocketProtocolInitialized = true;
        webSocketProtocol.configure(config);
    }

    public void initEndpointMapper() {
        String s = servletConfig.getInitParameter(ApplicationConfig.ENDPOINT_MAPPER);
        if (s != null) {
            try {
                endpointMapper = newClassInstance(EndpointMapper.class, (Class<EndpointMapper>) IOUtils.loadClass(this.getClass(), s));
                logger.info("Installed EndpointMapper {} ", s);
            } catch (Exception ex) {
                logger.error("Cannot load the EndpointMapper {}", s, ex);
            }
        }
        endpointMapper.configure(config);
    }

    protected void closeAtmosphereResource() {
        for (AtmosphereResource r : config.resourcesFactory().findAll()) {
            try {
                r.resume().close();
            } catch (IOException e) {
                logger.trace("", e);
            }
        }
    }

    public AtmosphereFramework destroy() {

        if (isDestroyed.getAndSet(true)) return this;

        onPreDestroy();

        closeAtmosphereResource();
        destroyInterceptors();

        // Invoke ShutdownHook.
        config.destroy();

        BroadcasterFactory factory = broadcasterFactory;
        if (factory != null) {
            factory.destroy();
        }

        if (asyncSupport != null && AsynchronousProcessor.class.isAssignableFrom(asyncSupport.getClass())) {
            ((AsynchronousProcessor) asyncSupport).shutdown();
        }

        // We just need one bc to shutdown the shared thread pool
        for (Entry<String, AtmosphereHandlerWrapper> entry : atmosphereHandlers.entrySet()) {
            AtmosphereHandlerWrapper handlerWrapper = entry.getValue();
            handlerWrapper.atmosphereHandler.destroy();
        }

        if (metaBroadcaster != null) metaBroadcaster.destroy();
        if (arFactory != null) arFactory.destroy();
        if (sessionFactory != null) sessionFactory.destroy();

        WebSocketProcessorFactory.getDefault().destroy();

        ExecutorsFactory.reset(config);

        resetStates();

        onPostDestroy();

        try {
            if (this.shutdownHook != null) {
                Runtime.getRuntime().removeShutdownHook(this.shutdownHook);
                shutdownHook = null;
            }
        } catch (IllegalStateException ex) {
            logger.trace("", ex);
        }

        return this;
    }

    protected void destroyInterceptors() {
        for (AtmosphereHandlerWrapper w : atmosphereHandlers.values()) {
            if (w.interceptors != null) {
                for (AtmosphereInterceptor i : w.interceptors) {
                    try {
                        i.destroy();
                    } catch (Throwable ex) {
                        logger.warn("", ex);
                    }
                }
            }
        }
    }

    public AtmosphereFramework resetStates() {
        isInit = false;
        executeFirstSet = false;

        broadcasterFilters.clear();
        asyncSupportListeners.clear();
        possibleComponentsCandidate.clear();
        initParams.clear();
        atmosphereHandlers.clear();
        broadcasterTypes.clear();
        objectFactoryType.clear();
        inspectors.clear();
        broadcasterListeners.clear();
        packages.clear();
        annotationPackages.clear();
        excludedInterceptors.clear();
        broadcasterCacheListeners.clear();
        filterManipulators.clear();
        interceptors.clear();

        broadcasterFactory = null;
        arFactory = null;
        metaBroadcaster = null;
        sessionFactory = null;
        annotationFound = false;
        return this;
    }

    protected void loadMetaService() {
        try {
            Map<String, MetaServiceAction> config = (Map<String, MetaServiceAction>) servletConfig.getServletContext().getAttribute(AtmosphereFramework.MetaServiceAction.class.getName());
            if (config == null) {
                config = IOUtils.readServiceFile(metaServicePath + AtmosphereFramework.class.getName());
            }

            for (final Map.Entry<String, MetaServiceAction> action : config.entrySet()) {
                final Class c = IOUtils.loadClass(AtmosphereFramework.class, action.getKey());
                action.getValue().apply(this, c);
            }
        } catch (Exception ex) {
            logger.error("", ex);
        }
    }

    /**
     * Load AtmosphereHandler defined under META-INF/atmosphere.xml.
     *
     * @param stream The input stream we read from.
     * @param c      The classloader
     */
    protected void loadAtmosphereDotXml(InputStream stream, URLClassLoader c)
            throws IOException, ServletException {

        if (stream == null) {
            return;
        }

        logger.info("Found Atmosphere Configuration under {}", atmosphereDotXmlPath);
        AtmosphereConfigReader.getInstance().parse(config, stream);
        AtmosphereHandler handler = null;
        for (AtmosphereHandlerConfig atmoHandler : config.getAtmosphereHandlerConfig()) {
            try {
                if (!atmoHandler.getClassName().startsWith("@")) {
                    if (!ReflectorServletProcessor.class.getName().equals(atmoHandler.getClassName())) {
                        handler = newClassInstance(AtmosphereHandler.class,
                                (Class<AtmosphereHandler>) IOUtils.loadClass(this.getClass(), atmoHandler.getClassName()));
                    } else {
                        handler = newClassInstance(AtmosphereHandler.class, ReflectorServletProcessor.class);
                    }
                    logger.info("Installed AtmosphereHandler {} mapped to context-path: {}", handler, atmoHandler.getContextRoot());
                }

                for (ApplicationConfiguration a : atmoHandler.getApplicationConfig()) {
                    initParams.put(a.getParamName(), a.getParamValue());
                }

                for (FrameworkConfiguration a : atmoHandler.getFrameworkConfig()) {
                    initParams.put(a.getParamName(), a.getParamValue());
                }

                for (AtmosphereHandlerProperty handlerProperty : atmoHandler.getProperties()) {

                    if (handlerProperty.getValue() != null && handlerProperty.getValue().indexOf("jersey") != -1) {
                        initParams.put(DISABLE_ONSTATE_EVENT, "true");
                        useStreamForFlushingComments = true;
                        broadcasterClassName = lookupDefaultBroadcasterType(JERSEY_BROADCASTER);
                        broadcasterFactory.destroy();
                        broadcasterFactory = null;
                        configureBroadcasterFactory();
                        configureBroadcaster();
                    }

                    if (handler != null) {
                        IntrospectionUtils.setProperty(handler, handlerProperty.getName(), handlerProperty.getValue());
                        IntrospectionUtils.addProperty(handler, handlerProperty.getName(), handlerProperty.getValue());
                    }
                }

                sessionSupport(Boolean.valueOf(atmoHandler.getSupportSession()));

                if (handler != null) {
                    String broadcasterClass = atmoHandler.getBroadcaster();
                    Broadcaster b;
                    /**
                     * If there is more than one AtmosphereHandler defined, their Broadcaster
                     * may clash each other with the BroadcasterFactory. In that case we will use the
                     * last one defined.
                     */
                    if (broadcasterClass != null) {
                        broadcasterClassName = broadcasterClass;
                        ClassLoader cl = Thread.currentThread().getContextClassLoader();
                        Class<? extends Broadcaster> bc = (Class<? extends Broadcaster>) cl.loadClass(broadcasterClassName);
                        broadcasterFactory = newClassInstance(BroadcasterFactory.class, DefaultBroadcasterFactory.class);
                        broadcasterFactory.configure(bc, broadcasterLifeCyclePolicy, config);
                    }

                    b = broadcasterFactory.lookup(atmoHandler.getContextRoot(), true);

                    AtmosphereHandlerWrapper wrapper = new AtmosphereHandlerWrapper(handler, b, config);
                    addMapping(atmoHandler.getContextRoot(), wrapper);

                    String bc = atmoHandler.getBroadcasterCache();
                    if (bc != null) {
                        broadcasterCacheClassName = bc;
                    }

                    if (atmoHandler.getCometSupport() != null) {
                        asyncSupport = (AsyncSupport) c.loadClass(atmoHandler.getCometSupport())
                                .getDeclaredConstructor(new Class[]{AtmosphereConfig.class})
                                .newInstance(new Object[]{config});
                    }

                    if (atmoHandler.getBroadcastFilterClasses() != null) {
                        broadcasterFilters.addAll(atmoHandler.getBroadcastFilterClasses());
                    }

                    LinkedList<AtmosphereInterceptor> l = new LinkedList<AtmosphereInterceptor>();
                    if (atmoHandler.getAtmosphereInterceptorClasses() != null) {
                        for (String a : atmoHandler.getAtmosphereInterceptorClasses()) {
                            try {
                                AtmosphereInterceptor ai = newClassInstance(AtmosphereInterceptor.class,
                                        (Class<AtmosphereInterceptor>) IOUtils.loadClass(getClass(), a));
                                l.add(ai);
                            } catch (Throwable e) {
                                logger.warn("", e);
                            }
                        }
                    }
                    addInterceptorToWrapper(wrapper, l);

                    if (!l.isEmpty()) {
                        logger.info("Installed AtmosphereInterceptor {} mapped to AtmosphereHandler {}", l, atmoHandler.getClassName());
                    }
                }
            } catch (Throwable t) {
                logger.warn("Unable to load AtmosphereHandler class: " + atmoHandler.getClassName(), t);
                throw new ServletException(t);
            }

        }
    }

    /**
     * Set the {@link AsyncSupport} implementation. Make sure you don't set an implementation that only works on
     * some container. See {@link BlockingIOCometSupport} for an example.
     *
     * @param asyncSupport
     */
    public AtmosphereFramework setAsyncSupport(AsyncSupport asyncSupport) {
        this.asyncSupport = asyncSupport;
        return this;
    }

    /**
     * @param asyncSupport
     * @return
     * @Deprecated - Use {@link #setAsyncSupport(AsyncSupport)}
     */
    public AtmosphereFramework setCometSupport(AsyncSupport asyncSupport) {
        return setAsyncSupport(asyncSupport);
    }

    /**
     * Return the current {@link AsyncSupport}.
     *
     * @return the current {@link AsyncSupport}
     */
    public AsyncSupport getAsyncSupport() {
        return asyncSupport;
    }

    /**
     * Return the current {@link AsyncSupport}.
     *
     * @return the current {@link AsyncSupport}
     * @deprecated Use getAsyncSupport
     */
    public AsyncSupport getCometSupport() {
        return asyncSupport;
    }

    /**
     * Returns an instance of AsyncSupportResolver {@link AsyncSupportResolver}.
     *
     * @return CometSupportResolver
     */
    protected AsyncSupportResolver createAsyncSupportResolver() {
        return new DefaultAsyncSupportResolver(config);
    }


    /**
     * Auto detect the underlying Servlet Container we are running on.
     */
    protected void autoDetectContainer() {
        // Was defined in atmosphere.xml
        if (getAsyncSupport() == null) {
            setAsyncSupport(createAsyncSupportResolver()
                    .resolve(useNativeImplementation, useBlockingImplementation, useServlet30));
        }
    }

    /**
     * Auto detect instance of {@link AtmosphereHandler} in case META-INF/atmosphere.xml
     * is missing.
     *
     * @param servletContext {@link ServletContext}
     * @param classloader    {@link URLClassLoader} to load the class.
     * @throws java.net.MalformedURLException
     * @throws java.net.URISyntaxException
     */
    public void autoDetectAtmosphereHandlers(ServletContext servletContext, URLClassLoader classloader)
            throws MalformedURLException, URISyntaxException {

        // If Handler has been added
        if (!atmosphereHandlers.isEmpty()) return;

        logger.info("Auto detecting atmosphere handlers {}", handlersPath);

        String realPath = servletContext.getRealPath(handlersPath);

        // Weblogic bug
        if (realPath == null) {
            URL u = servletContext.getResource(handlersPath);
            if (u == null) return;
            realPath = u.getPath();
        }

        loadAtmosphereHandlersFromPath(classloader, realPath);
    }

    public void loadAtmosphereHandlersFromPath(URLClassLoader classloader, String realPath) {
        File file = new File(realPath);

        if (file.exists() && file.isDirectory()) {
            getFiles(file);
            scanDone = true;

            for (String className : possibleComponentsCandidate) {
                try {
                    className = className.replace('\\', '/');
                    className = className.replaceFirst("^.*/(WEB-INF|target)(?:/scala-[^/]+)?/(test-)?classes/(.*)\\.class", "$3").replace("/", ".");
                    Class<?> clazz = classloader.loadClass(className);

                    if (AtmosphereHandler.class.isAssignableFrom(clazz)) {
                        AtmosphereHandler handler = newClassInstance(AtmosphereHandler.class, (Class<AtmosphereHandler>) clazz);
                        addMapping("/" + handler.getClass().getSimpleName(),
                                new AtmosphereHandlerWrapper(broadcasterFactory, handler, "/" + handler.getClass().getSimpleName(), config));
                        logger.info("Installed AtmosphereHandler {} mapped to context-path: {}", handler, handler.getClass().getName());
                    }
                } catch (Throwable t) {
                    logger.trace("failed to load class as an AtmosphereHandler: " + className, t);
                }
            }
        }
    }

    /**
     * Auto detect instance of {@link org.atmosphere.websocket.WebSocketHandler} in case META-INF/atmosphere.xml
     * is missing.
     *
     * @param servletContext {@link ServletContext}
     * @param classloader    {@link URLClassLoader} to load the class.
     * @throws java.net.MalformedURLException
     * @throws java.net.URISyntaxException
     */
    protected void autoDetectWebSocketHandler(ServletContext servletContext, URLClassLoader classloader)
            throws MalformedURLException, URISyntaxException {

        if (hasNewWebSocketProtocol) return;

        logger.info("Auto detecting WebSocketHandler in {}", handlersPath);
        loadWebSocketFromPath(classloader, realPath(servletContext, handlersPath));
    }

    protected void loadWebSocketFromPath(URLClassLoader classloader, String realPath) {
        File file = new File(realPath);

        if (file.exists() && file.isDirectory()) {
            getFiles(file);
            scanDone = true;

            for (String className : possibleComponentsCandidate) {
                try {
                    className = className.replace('\\', '/');
                    className = className.replaceFirst("^.*/(WEB-INF|target)(?:/scala-[^/]+)?/(test-)?classes/(.*)\\.class", "$3").replace("/", ".");
                    Class<?> clazz = classloader.loadClass(className);

                    if (WebSocketProtocol.class.isAssignableFrom(clazz)) {
                        webSocketProtocol = (WebSocketProtocol) newClassInstance(WebSocketProtocol.class, (Class<WebSocketProtocol>) clazz);
                        logger.info("Installed WebSocketProtocol {}", webSocketProtocol);
                    }
                } catch (Throwable t) {
                    logger.trace("failed to load class as an WebSocketProtocol: " + className, t);
                }
            }
        }
    }

    /**
     * Get a list of possible candidates to load as {@link AtmosphereHandler}.
     *
     * @param f the real path {@link File}
     */
    private void getFiles(File f) {
        if (scanDone) return;

        File[] files = f.listFiles();
        for (File test : files) {
            if (test.isDirectory()) {
                getFiles(test);
            } else {
                String clazz = test.getAbsolutePath();
                if (clazz.endsWith(".class")) {
                    possibleComponentsCandidate.add(clazz);
                }
            }
        }
    }

    /**
     * Configure some attributes on the {@link AtmosphereRequest}.
     *
     * @param req {@link AtmosphereRequest}
     */
    public AtmosphereFramework configureRequestResponse(AtmosphereRequest req, AtmosphereResponse res) throws UnsupportedEncodingException {
        req.setAttribute(PROPERTY_USE_STREAM, useStreamForFlushingComments);
        req.setAttribute(BROADCASTER_CLASS, broadcasterClassName);
        req.setAttribute(ATMOSPHERE_CONFIG, config);
        req.setAttribute(THROW_EXCEPTION_ON_CLONED_REQUEST, "" + config.isThrowExceptionOnCloned());

        boolean skip = true;
        String s = config.getInitParameter(ALLOW_QUERYSTRING_AS_REQUEST);
        if (s != null) {
            skip = Boolean.valueOf(s);
        }

        if (!skip || req.getAttribute(WEBSOCKET_SUSPEND) == null) {
            Map<String, String> headers = configureQueryStringAsRequest(req);
            String body = headers.remove(ATMOSPHERE_POST_BODY);
            if (body != null && body.isEmpty()) {
                body = null;
            }

            // Reconfigure the request. Clear the Atmosphere queryString
            req.headers(headers)
                    .method(body != null && req.getMethod().equalsIgnoreCase("GET") ? "POST" : req.getMethod());

            if (body != null) {
                req.body(URLDecoder.decode(body, req.getCharacterEncoding() == null ? "UTF-8" : req.getCharacterEncoding()));
            }
        }

        s = req.getHeader(X_ATMOSPHERE_TRACKING_ID);

        // Lookup for websocket
        if (s == null || s.equals("0")) {
            String unique = config.getInitParameter(ApplicationConfig.UNIQUE_UUID_WEBSOCKET);
            if (unique != null && Boolean.valueOf(unique)) {
                s = (String) req.getAttribute(SUSPENDED_ATMOSPHERE_RESOURCE_UUID);
            }
        }

        if (s == null || s.equals("0")) {
            s = config.uuidProvider().generateUuid();
            res.setHeader(HeaderConfig.X_FIRST_REQUEST, "true");
            res.setHeader(X_ATMOSPHERE_TRACKING_ID, s);
        } else {
            // This may breaks 1.0.0 application because the WebSocket's associated AtmosphereResource will
            // all have the same UUID, and retrieving the original one for WebSocket, so we don't set it at all.
            // Null means it is not an HTTP request.
            if (req.resource() == null) {
                res.setHeader(X_ATMOSPHERE_TRACKING_ID, s);
            } else if (req.getAttribute(WebSocket.WEBSOCKET_INITIATED) == null) {
                // WebSocket reconnect, in case an application manually set the header
                // (impossible to retrieve the headers normally with WebSocket or SSE)
                res.setHeader(X_ATMOSPHERE_TRACKING_ID, s);
            }
        }

        if (req.getAttribute(SUSPENDED_ATMOSPHERE_RESOURCE_UUID) == null) {
            req.setAttribute(SUSPENDED_ATMOSPHERE_RESOURCE_UUID, s);
        }
        return this;
    }

    /**
     * Invoke the proprietary {@link AsyncSupport}.
     *
     * @param req
     * @param res
     * @return an {@link Action}
     * @throws IOException
     * @throws ServletException
     */
    public Action doCometSupport(AtmosphereRequest req, AtmosphereResponse res) throws IOException, ServletException {

        if (isDestroyed.get()) return Action.CANCELLED;

        Action a = null;
        try {
            configureRequestResponse(req, res);
            a = asyncSupport.service(req, res);
        } catch (IllegalStateException ex) {
            boolean isJBoss = ex.getMessage() == null ? false : ex.getMessage().startsWith("JBoss failed");
            if (ex.getMessage() != null && (ex.getMessage().startsWith("Tomcat failed") || isJBoss)) {
                if (!isFilter) {
                    logger.warn("Failed using comet support: {}, error: {} Is the NIO or APR Connector enabled?", asyncSupport.getClass().getName(),
                            ex.getMessage());
                }
                logger.error("If you have more than one Connector enabled, make sure they both use the same protocol, " +
                        "e.g NIO/APR or HTTP for all. If not, {} will be used and cannot be changed.", BlockingIOCometSupport.class.getName(), ex);

                AsyncSupport current = asyncSupport;
                asyncSupport = asyncSupport.supportWebSocket() && !isJBoss ? new Tomcat7BIOSupportWithWebSocket(config) : new BlockingIOCometSupport(config);
                if (current instanceof AsynchronousProcessor) {
                    ((AsynchronousProcessor) current).shutdown();
                }

                asyncSupport.init(config.getServletConfig());
                logger.warn("Using " + asyncSupport.getClass().getName());

                a = asyncSupport.service(req, res);
            } else {
                logger.error("AtmosphereFramework exception", ex);
                throw ex;
            }
        } finally {
            if (a != null) {
                notify(a.type(), req, res);
            }

            if (!externalizeDestroy) {
                if (req != null && a != null && a.type() != Action.TYPE.SUSPEND) {
                    req.destroy();
                    res.destroy();
                    notify(Action.TYPE.DESTROYED, req, res);
                }
            }
        }
        return a;
    }

    /**
     * Return the default {@link Broadcaster} class name.
     *
     * @return the broadcasterClassName
     */
    public String getDefaultBroadcasterClassName() {
        return broadcasterClassName;
    }

    /**
     * Set the default {@link Broadcaster} class name.
     *
     * @param bccn the broadcasterClassName to set
     */
    public AtmosphereFramework setDefaultBroadcasterClassName(String bccn) {
        if (isBroadcasterSpecified) {
            logger.trace("Broadcaster {} already set in web.xml", broadcasterClassName);
            return this;
        }
        isBroadcasterSpecified = true;

        broadcasterClassName = bccn;

        // Must reconfigure.
        broadcasterFactory = null;
        configureBroadcasterFactory();

        // We must recreate all previously created Broadcaster.
        for (AtmosphereHandlerWrapper w : atmosphereHandlers.values()) {
            // If case one listener is initializing the framework.
            if (w.broadcaster != null) {
                w.broadcaster = broadcasterFactory.lookup(w.broadcaster.getID(), true);
            }
        }
        return this;
    }

    /**
     * <tt>true</tt> if Atmosphere uses {@link AtmosphereResponseImpl#getOutputStream()}
     * by default for write operation.
     *
     * @return the useStreamForFlushingComments
     */
    public boolean isUseStreamForFlushingComments() {
        return useStreamForFlushingComments;
    }

    public boolean isUseServlet30() {
        return useServlet30;
    }

    /**
     * Set to <tt>true</tt> so Atmosphere uses {@link AtmosphereResponseImpl#getOutputStream()}
     * by default for write operation. Default is false.
     *
     * @param useStreamForFlushingComments the useStreamForFlushingComments to set
     */
    public AtmosphereFramework setUseStreamForFlushingComments(boolean useStreamForFlushingComments) {
        this.useStreamForFlushingComments = useStreamForFlushingComments;
        return this;
    }

    /**
     * Get the {@link BroadcasterFactory} which is used by Atmosphere to construct
     * {@link Broadcaster}.
     *
     * @return {@link BroadcasterFactory}
     */
    public BroadcasterFactory getBroadcasterFactory() {
        if (broadcasterFactory == null) {
            configureBroadcasterFactory();
        }
        return broadcasterFactory;
    }

    /**
     * Set the {@link BroadcasterFactory} which is used by Atmosphere to construct
     * {@link Broadcaster}.
     *
     * @return {@link BroadcasterFactory}
     */
    public AtmosphereFramework setBroadcasterFactory(final BroadcasterFactory broadcasterFactory) {
        this.broadcasterFactory = broadcasterFactory;
        configureBroadcaster();
        return this;
    }

    /**
     * Return the {@link org.atmosphere.cpr.BroadcasterCache} class name.
     *
     * @return the {@link org.atmosphere.cpr.BroadcasterCache} class name
     */
    public String getBroadcasterCacheClassName() {
        return broadcasterCacheClassName;
    }

    /**
     * Set the {@link org.atmosphere.cpr.BroadcasterCache} class name.
     *
     * @param broadcasterCacheClassName
     */
    public AtmosphereFramework setBroadcasterCacheClassName(String broadcasterCacheClassName) {
        this.broadcasterCacheClassName = broadcasterCacheClassName;
        return this;
    }

    /**
     * Add a new Broadcaster class name that AtmosphereServlet can use when initializing requests, and when the
     * atmosphere.xml broadcaster element is unspecified.
     *
     * @param broadcasterTypeString
     */
    public AtmosphereFramework addBroadcasterType(String broadcasterTypeString) {
        broadcasterTypes.add(broadcasterTypeString);
        return this;
    }

    public ConcurrentLinkedQueue<String> broadcasterTypes() {
        return broadcasterTypes;
    }

    public String getWebSocketProtocolClassName() {
        return webSocketProtocolClassName;
    }

    public AtmosphereFramework setWebSocketProtocolClassName(String webSocketProtocolClassName) {
        hasNewWebSocketProtocol = true;
        this.webSocketProtocolClassName = webSocketProtocolClassName;
        return this;
    }

    public Map<String, AtmosphereHandlerWrapper> getAtmosphereHandlers() {
        return atmosphereHandlers;
    }

    protected Map<String, String> configureQueryStringAsRequest(AtmosphereRequest request) {
        Map<String, String> headers = new HashMap<String, String>();

        StringBuilder q = new StringBuilder();
        try {
            String qs = request.getQueryString();
            if (qs != null && !qs.isEmpty()) {
                String[] params = qs.split("&");
                String[] s;
                for (String p : params) {
                    s = p.split("=", 2);
                    final String header = s[0];
                    final String value = s.length > 1 ? s[1] : "";

                    if (header.equalsIgnoreCase("Content-Type")) {
                        // Use the one set by the user first.
                        if (request.getContentType() == null ||
                                !request.getContentType().equalsIgnoreCase(s.length > 1 ? value : "")) {
                            request.contentType(s.length > 1 ? URLDecoder.decode(value, "UTF-8") : "");
                        }
                    }
                    if (!header.isEmpty()
                            && !header.toLowerCase().startsWith("x-atmo")
                            && !header.equalsIgnoreCase(HeaderConfig.X_HEARTBEAT_SERVER)
                            && !header.equalsIgnoreCase("Content-Type")
                            && !header.equalsIgnoreCase("_")) {
                        q.append(header).append("=").append(s.length > 1 ? value : "").append("&");
                    }
                    headers.put(header, s.length > 1 ? value : "");
                }
            }
        } catch (Exception ex) {
            logger.error("Unable to parse query string", ex);
        }
        String disallowModifyQueryString = config.getInitParameter(ApplicationConfig.DISALLOW_MODIFY_QUERYSTRING);
        if (disallowModifyQueryString == null ||
                disallowModifyQueryString.length() == 0 || "false".equalsIgnoreCase(disallowModifyQueryString)) {
            if (q.length() > 0) {
                q.deleteCharAt(q.length() - 1);
            }
            request.queryString(q.toString());
        }

        logger.trace("Query String translated to headers {}", headers);
        return headers;
    }

    public WebSocketProtocol getWebSocketProtocol() {
        // TODO: Spagetthi code, needs to rework.
        // Make sure we initialized the WebSocketProtocol
        initWebSocket();
        return webSocketProtocol;
    }

    public boolean isUseNativeImplementation() {
        return useNativeImplementation;
    }

    public AtmosphereFramework setUseNativeImplementation(boolean useNativeImplementation) {
        this.useNativeImplementation = useNativeImplementation;
        return this;
    }

    public boolean isUseBlockingImplementation() {
        return useBlockingImplementation;
    }

    public AtmosphereFramework setUseBlockingImplementation(boolean useBlockingImplementation) {
        this.useBlockingImplementation = useBlockingImplementation;
        return this;
    }

    public String getAtmosphereDotXmlPath() {
        return atmosphereDotXmlPath;
    }

    public AtmosphereFramework setAtmosphereDotXmlPath(String atmosphereDotXmlPath) {
        this.atmosphereDotXmlPath = atmosphereDotXmlPath;
        return this;
    }

    public String getHandlersPath() {
        return handlersPath;
    }

    public AtmosphereFramework setHandlersPath(String handlersPath) {
        this.handlersPath = handlersPath;
        return this;
    }

    /**
     * Return the location of the JARs containing the application classes. Default is WEB-INF/lib.
     *
     * @return the location of the JARs containing the application classes. Default is WEB-INF/lib
     */
    public String getLibPath() {
        return libPath;
    }

    /**
     * Set the location of the JARs containing the application.
     *
     * @param libPath the location of the JARs containing the application.
     * @return this
     */
    public AtmosphereFramework setLibPath(String libPath) {
        this.libPath = libPath;
        return this;
    }

    /**
     * The current {@link org.atmosphere.websocket.WebSocketProcessor} used to handle websocket requests.
     *
     * @return {@link org.atmosphere.websocket.WebSocketProcessor}
     */
    public String getWebSocketProcessorClassName() {
        return webSocketProcessorClassName;
    }

    /**
     * Set the {@link org.atmosphere.websocket.WebSocketProcessor} class name used to process WebSocket requests. Default is
     * {@link DefaultWebSocketProcessor}
     *
     * @param webSocketProcessorClassName {@link org.atmosphere.websocket.WebSocketProcessor}
     * @return this
     */
    public AtmosphereFramework setWebsocketProcessorClassName(String webSocketProcessorClassName) {
        this.webSocketProcessorClassName = webSocketProcessorClassName;
        return this;
    }

    /**
     * Add an {@link AtmosphereInterceptor} implementation. The adding order of {@link AtmosphereInterceptor} will be used, e.g
     * the first added {@link AtmosphereInterceptor} will always be called first.
     *
     * @param c {@link AtmosphereInterceptor}
     * @return this
     */
    public AtmosphereFramework interceptor(AtmosphereInterceptor c) {
        if (!checkDuplicate(c)) {
            interceptors.add(c);
            if (isInit) {
                addInterceptorToAllWrappers(c);
            }
        }
        return this;
    }

    protected void addDefaultOrAppInterceptors() {
        for (AtmosphereInterceptor c : interceptors) {
            addInterceptorToAllWrappers(c);
        }
    }

    protected void addInterceptorToAllWrappers(AtmosphereInterceptor c) {
        c.configure(config);
        InvokationOrder.PRIORITY p = InvokationOrder.class.isAssignableFrom(c.getClass()) ? InvokationOrder.class.cast(c).priority() : InvokationOrder.AFTER_DEFAULT;

        logger.info("Installed AtmosphereInterceptor {} with priority {} ", c, p.name());
        //need insert this new interceptor into all the existing handlers
        for (AtmosphereHandlerWrapper wrapper : atmosphereHandlers.values()) {
            addInterceptorToWrapper(wrapper, c);
        }
    }

    protected void addInterceptorToWrapper(AtmosphereHandlerWrapper wrapper, AtmosphereInterceptor c) {
        if (!checkDuplicate(wrapper.interceptors, c.getClass())) {
            wrapper.interceptors.add(c);
            Collections.sort(wrapper.interceptors, new InterceptorComparator());
        }
    }

    protected void addInterceptorToWrapper(AtmosphereHandlerWrapper wrapper, List<AtmosphereInterceptor> interceptors) {

        for (AtmosphereInterceptor c : this.interceptors) {
            addInterceptorToWrapper(wrapper, c);
        }

        for (AtmosphereInterceptor c : interceptors) {
            addInterceptorToWrapper(wrapper, c);
            c.configure(config);
        }
    }

    /**
     * <p>
     * Checks if an instance of the specified {@link AtmosphereInterceptor} implementation exists in the
     * {@link #interceptors}.
     * </p>
     *
     * @param c the implementation
     * @return {@code false} if an instance of the same interceptor's class already exists in  {@link #interceptors}, {@code true} otherwise
     */
    private boolean checkDuplicate(final AtmosphereInterceptor c) {
        return checkDuplicate(interceptors, c.getClass());
    }

    /**
     * <p>
     * Checks in the specified list if there is at least one instance of the given
     * {@link AtmosphereInterceptor interceptor} implementation class.
     * </p>
     *
     * @param interceptorList the interceptors
     * @param c               the interceptor class
     * @return {@code false} if an instance of the class already exists in the list, {@code true} otherwise
     */
    private boolean checkDuplicate(final List<AtmosphereInterceptor> interceptorList, Class<? extends AtmosphereInterceptor> c) {
        for (final AtmosphereInterceptor i : interceptorList) {
            if (i.getClass().equals(c)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Return the list of {@link AtmosphereInterceptor}.
     *
     * @return the list of {@link AtmosphereInterceptor}
     */
    public LinkedList<AtmosphereInterceptor> interceptors() {
        return interceptors;
    }

    /**
     * Set the {@link AnnotationProcessor} class name.
     *
     * @param annotationProcessorClassName the {@link AnnotationProcessor} class name.
     * @return this
     */
    public AtmosphereFramework annotationProcessorClassName(String annotationProcessorClassName) {
        this.annotationProcessorClassName = annotationProcessorClassName;
        return this;
    }

    /**
     * Add an {@link AsyncSupportListener}.
     *
     * @param asyncSupportListener an {@link AsyncSupportListener}
     * @return this;
     */
    public AtmosphereFramework asyncSupportListener(AsyncSupportListener asyncSupportListener) {
        asyncSupportListeners.add(asyncSupportListener);
        return this;
    }

    /**
     * Return the list of {@link AsyncSupportListener}s.
     *
     * @return
     */
    public List<AsyncSupportListener> asyncSupportListeners() {
        return asyncSupportListeners;
    }

    /**
     * Add {@link BroadcasterListener} to all created {@link Broadcaster}s.
     */
    public AtmosphereFramework addBroadcasterListener(BroadcasterListener b) {
        broadcasterFactory.addBroadcasterListener(b);
        broadcasterListeners.add(b);
        return this;
    }

    /**
     * Add {@link BroadcasterCacheListener} to the {@link BroadcasterCache}.
     */
    public AtmosphereFramework addBroadcasterCacheListener(BroadcasterCacheListener b) {
        broadcasterCacheListeners.add(b);
        return this;
    }

    public List<BroadcasterCacheListener> broadcasterCacheListeners() {
        return broadcasterCacheListeners;
    }

    /**
     * Add a {@link BroadcasterCacheInspector} which will be associated with the defined {@link BroadcasterCache}.
     *
     * @param b {@link BroadcasterCacheInspector}
     * @return this;
     */
    public AtmosphereFramework addBroadcasterCacheInjector(BroadcasterCacheInspector b) {
        inspectors.add(b);
        return this;
    }

    /**
     * Return the list of {@link BroadcasterCacheInspector}s.
     *
     * @return the list of {@link BroadcasterCacheInspector}s
     */
    public ConcurrentLinkedQueue<BroadcasterCacheInspector> inspectors() {
        return inspectors;
    }

    /**
     * Return a configured instance of {@link AtmosphereConfig}.
     *
     * @return a configured instance of {@link AtmosphereConfig}
     */
    public AtmosphereConfig getAtmosphereConfig() {
        return config;
    }

    /**
     * Return the {@link ServletContext}
     *
     * @return the {@link ServletContext}
     */
    public ServletContext getServletContext() {
        return servletConfig.getServletContext();
    }

    public ServletConfig getServletConfig() {
        return servletConfig;
    }

    /**
     * Return the list of {@link BroadcastFilter}s.
     *
     * @return the list of {@link BroadcastFilter}s
     */
    public List<String> broadcasterFilters() {
        return broadcasterFilters;
    }

    /**
     * Add a {@link BroadcastFilter}.
     *
     * @return
     */
    public AtmosphereFramework broadcasterFilters(BroadcastFilter f) {
        broadcasterFilters.add(f.getClass().getName());

        for (Broadcaster b : config.getBroadcasterFactory().lookupAll()) {
            b.getBroadcasterConfig().addFilter(f);
        }
        return this;
    }

    /**
     * Returns true if {@link java.util.concurrent.ExecutorService} is shared among all components.
     *
     * @return true if {@link java.util.concurrent.ExecutorService} is shared amongst all components
     */
    public boolean isShareExecutorServices() {
        return sharedThreadPools;
    }

    /**
     * Set to true to have a {@link java.util.concurrent.ExecutorService} shared among all components.
     *
     * @param sharedThreadPools
     * @return this
     */
    public AtmosphereFramework shareExecutorServices(boolean sharedThreadPools) {
        this.sharedThreadPools = sharedThreadPools;
        return this;
    }

    protected void autoConfigureService(ServletContext sc) throws IOException {
        String path = handlersPath != DEFAULT_HANDLER_PATH ? handlersPath : realPath(sc, handlersPath);
        try {
            annotationProcessor = newClassInstance(AnnotationProcessor.class,
                    (Class<AnnotationProcessor>) IOUtils.loadClass(getClass(), annotationProcessorClassName));
            logger.info("Atmosphere is using {} for processing annotation", annotationProcessorClassName);

            annotationProcessor.configure(config);

            if (!packages.isEmpty()) {
                for (String s : packages) {
                    annotationProcessor.scan(s);
                }
            }

            // Second try.
            if (!annotationFound) {
                if (path != null) {
                    annotationProcessor.scan(new File(path));
                }

                // Always scan library
                String pathLibs = libPath != DEFAULT_LIB_PATH ? libPath : realPath(sc, DEFAULT_LIB_PATH);
                if (pathLibs != null) {
                    File libFolder = new File(pathLibs);
                    File jars[] = libFolder.listFiles(new FilenameFilter() {

                        @Override
                        public boolean accept(File arg0, String arg1) {
                            return arg1.endsWith(".jar");
                        }
                    });

                    if (jars != null) {
                        for (File file : jars) {
                            annotationProcessor.scan(file);
                        }
                    }
                }
            }

            if (!annotationFound && allowAllClassesScan) {
                logger.debug("Scanning all classes on the classpath");
                annotationProcessor.scanAll();
            }
        } catch (Throwable e) {
            logger.error("", e);
            return;
        } finally {
            if (annotationProcessor != null) {
                annotationProcessor.destroy();
            }
        }
    }

    /**
     * The current {@link EndpointMapper} used to map requests to {@link AtmosphereHandler}.
     *
     * @return {@link EndpointMapper}
     */
    public EndpointMapper<AtmosphereHandlerWrapper> endPointMapper() {
        return endpointMapper;
    }

    /**
     * Set the {@link EndpointMapper}.
     *
     * @param endpointMapper {@link EndpointMapper}
     * @return this
     */
    public AtmosphereFramework endPointMapper(EndpointMapper endpointMapper) {
        this.endpointMapper = endpointMapper;
        return this;
    }

    /**
     * Add support for package detection of Atmosphere's Component.
     *
     * @param clazz a Class
     * @return this.
     */
    public AtmosphereFramework addAnnotationPackage(Class<?> clazz) {
        if (clazz.getPackage() == null) {
            logger.error("Class {} must have a package defined", clazz);
        } else {
            packages.add(clazz.getPackage().getName());
        }
        return this;
    }

    public AtmosphereFramework notify(Action.TYPE type, AtmosphereRequest request, AtmosphereResponse response) {
        for (AsyncSupportListener l : asyncSupportListeners()) {
            try {
                switch (type) {
                    case TIMEOUT:
                        l.onTimeout(request, response);
                        break;
                    case CANCELLED:
                        l.onClose(request, response);
                        break;
                    case SUSPEND:
                        l.onSuspend(request, response);
                        break;
                    case RESUME:
                        l.onResume(request, response);
                        break;
                    case DESTROYED:
                        l.onDestroyed(request, response);
                        break;
                }
            } catch (Throwable t) {
                logger.warn("", t);
            }
        }
        return this;
    }

    public AtmosphereFramework notifyDestroyed(String uuid) {
        for (AtmosphereResourceListener l : atmosphereResourceListeners()) {
            l.onDisconnect(uuid);
        }
        return this;
    }

    public AtmosphereFramework notifySuspended(String uuid) {
        for (AtmosphereResourceListener l : atmosphereResourceListeners()) {
            l.onSuspended(uuid);
        }
        return this;
    }

    /**
     * Add an {@link WebSocketHandler} mapped to "/*".
     * return this
     */
    public AtmosphereFramework addWebSocketHandler(WebSocketHandler handler) {
        addWebSocketHandler(ROOT_MASTER, handler);
        return this;
    }

    /**
     * Add an {@link WebSocketHandler} mapped to the path.
     * return this
     */
    public AtmosphereFramework addWebSocketHandler(String path, WebSocketHandler handler) {
        addWebSocketHandler(path, handler, REFLECTOR_ATMOSPHEREHANDLER, Collections.EMPTY_LIST);
        return this;
    }

    /**
     * Add an {@link WebSocketHandler} mapped to the path and the {@link AtmosphereHandler} in case {@link Broadcaster} are
     * used.
     *
     * @param path    a path
     * @param handler a {@link WebSocketHandler}
     * @param h       an {@link AtmosphereHandler}
     * @return this
     */
    public AtmosphereFramework addWebSocketHandler(String path, WebSocketHandler handler, AtmosphereHandler h) {
        addWebSocketHandler(path, handler, REFLECTOR_ATMOSPHEREHANDLER, Collections.EMPTY_LIST);
        return this;
    }

    /**
     * Add an {@link WebSocketHandler} mapped to the path and the {@link AtmosphereHandler} in case {@link Broadcaster} are
     * used.
     *
     * @param path    a path
     * @param handler a {@link WebSocketHandler}
     * @param h       an {@link AtmosphereHandler}
     * @param l       {@link AtmosphereInterceptor}
     * @return this
     */
    public AtmosphereFramework addWebSocketHandler(String path, WebSocketHandler handler, AtmosphereHandler h, List<AtmosphereInterceptor> l) {
        WebSocketProcessorFactory.getDefault().getWebSocketProcessor(this)
                .registerWebSocketHandler(path,
                        new WebSocketProcessor.WebSocketHandlerProxy(broadcasterFactory.lookup(path, true).getClass(), handler));
        addAtmosphereHandler(path, h, l);
        return this;
    }

    /**
     * Invoked when a {@link AnnotationProcessor} found an annotation.
     *
     * @param b true when found
     * @return this
     */
    public AtmosphereFramework annotationScanned(boolean b) {
        annotationFound = b;
        return this;
    }

    /**
     * Return true if the {@link #init()} has been sucessfully executed.
     *
     * @return true if the {@link #init()} has been sucessfully executed.
     */
    public boolean initialized() {
        return isInit;
    }

    public List<String> packages() {
        return packages;
    }

    /**
     * Return the list of packages the framework should look for {@link org.atmosphere.config.AtmosphereAnnotation}.
     *
     * @return the list of packages the framework should look for {@link org.atmosphere.config.AtmosphereAnnotation}
     */
    public List<String> customAnnotationPackages() {
        return annotationPackages;
    }

    /**
     * Add a package containing classes annotated with {@link org.atmosphere.config.AtmosphereAnnotation}.
     *
     * @param p a package
     * @return this;
     */
    public AtmosphereFramework addCustomAnnotationPackage(Class p) {
        annotationPackages.addLast(p.getPackage().getName());
        return this;
    }

    /**
     * Instantiate a class
     *
     * @param classType   The Required Class's Type
     * @param defaultType The default implementation of the Class's Type.
     * @return the an instance of defaultType
     * @throws InstantiationException
     * @throws IllegalAccessException
     */
    public <T, U extends T> T newClassInstance(Class<T> classType, Class<U> defaultType) throws InstantiationException, IllegalAccessException {
        return objectFactory.newClassInstance(classType, defaultType);
    }

    /**
     * Set an object used for class instantiation.
     * Allows for integration with dependency injection frameworks.
     *
     * @param objectFactory
     */
    public void objectFactory(AtmosphereObjectFactory objectFactory) {
        this.objectFactory = objectFactory;
        this.objectFactory.configure(config);
    }

    /**
     * If set to true, the task of finishing the request/response lifecycle will not be handled by this class.
     *
     * @param externalizeDestroy
     * @return this
     */
    public AtmosphereFramework externalizeDestroy(boolean externalizeDestroy) {
        this.externalizeDestroy = externalizeDestroy;
        return this;
    }

    /**
     * Return the {@link AnnotationProcessor}
     *
     * @return the {@link AnnotationProcessor}
     */
    public AnnotationProcessor annotationProcessor() {
        return annotationProcessor;
    }

    /**
     * Was a {@link Broadcaster} defined in web.xml or programmatically added.
     *
     * @return true is defined.
     */
    public boolean isBroadcasterSpecified() {
        return isBroadcasterSpecified;
    }

    protected void configureObjectFactory() {
        String s = config.getInitParameter(ApplicationConfig.OBJECT_FACTORY);
        if (s != null) {
            try {
                AtmosphereObjectFactory aci = (AtmosphereObjectFactory) IOUtils.loadClass(getClass(), s).newInstance();
                if (aci != null) {
                    logger.debug("Found ObjectFactory {}", aci.getClass().getName());
                    objectFactory(aci);
                }
            } catch (Exception ex) {
                logger.warn("Unable to load AtmosphereClassInstantiator instance", ex);
            }
        }

        if (!DefaultAtmosphereObjectFactory.class.isAssignableFrom(objectFactory.getClass())) {
            logger.trace("ObjectFactory already set to {}", objectFactory);
            return;
        }

    }

    /**
     * Exclude an {@link AtmosphereInterceptor} from being added, at startup, by Atmosphere. The default's {@link #DEFAULT_ATMOSPHERE_INTERCEPTORS}
     * are candidates for being excluded.
     *
     * @param interceptor an {@link AtmosphereInterceptor} class name
     * @return this
     */
    public AtmosphereFramework excludeInterceptor(String interceptor) {
        excludedInterceptors.add(interceptor);
        return this;
    }

    public AtmosphereFramework filterManipulator(BroadcasterConfig.FilterManipulator m) {
        filterManipulators.add(m);
        return this;
    }

    public List<BroadcasterConfig.FilterManipulator> filterManipulators() {
        return filterManipulators;
    }

    public boolean isAServletFilter() {
        return isFilter;
    }

    public ConcurrentLinkedQueue<String> objectFactoryType() {
        return objectFactoryType;
    }

    public String mappingRegex() {
        return mappingRegex;
    }

    public AtmosphereFramework mappingRegex(String mappingRegex) {
        this.mappingRegex = mappingRegex;
        return this;
    }

    public void setUseServlet30(boolean useServlet30) {
        this.useServlet30 = useServlet30;
    }

    public boolean webSocketEnabled() {
        return webSocketEnabled;
    }

    public AtmosphereFramework webSocketEnabled(boolean webSocketEnabled) {
        this.webSocketEnabled = webSocketEnabled;
        return this;
    }

    public String broadcasterLifeCyclePolicy() {
        return broadcasterLifeCyclePolicy;
    }

    public AtmosphereFramework broadcasterLifeCyclePolicy(String broadcasterLifeCyclePolicy) {
        this.broadcasterLifeCyclePolicy = broadcasterLifeCyclePolicy;
        return this;
    }

    public List<BroadcasterListener> broadcasterListeners() {
        return broadcasterListeners;
    }

    public boolean sharedThreadPools() {
        return sharedThreadPools;
    }

    public AtmosphereFramework sharedThreadPools(boolean sharedThreadPools) {
        this.sharedThreadPools = sharedThreadPools;
        return this;
    }

    public boolean allowAllClassesScan() {
        return allowAllClassesScan;
    }

    public AtmosphereFramework allowAllClassesScan(boolean allowAllClassesScan) {
        this.allowAllClassesScan = allowAllClassesScan;
        return this;
    }

    public AtmosphereObjectFactory objectFactory() {
        return objectFactory;
    }

    public boolean externalizeDestroy() {
        return externalizeDestroy;
    }

    public List<String> excludedInterceptors() {
        return excludedInterceptors;
    }

    public Class<? extends AtmosphereInterceptor>[] defaultInterceptors() {
        return DEFAULT_ATMOSPHERE_INTERCEPTORS.toArray(new Class[DEFAULT_ATMOSPHERE_INTERCEPTORS.size()]);
    }

    public AtmosphereResourceFactory atmosphereFactory() {
        if (arFactory == null) {
            configureAtmosphereResourceFactory();
        }
        return arFactory;
    }

    private AtmosphereFramework configureAtmosphereResourceFactory() {
        if (arFactory != null) return this;

        synchronized (this) {
            try {
                arFactory = newClassInstance(AtmosphereResourceFactory.class, DefaultAtmosphereResourceFactory.class);
            } catch (InstantiationException e) {
                logger.error("", e);
            } catch (IllegalAccessException e) {
                logger.error("", e);
            }
            arFactory.configure(config);
        }
        return this;
    }

    private AtmosphereFramework configureWebSocketFactory() {
        if (webSocketFactory != null) return this;

        synchronized (this) {
            try {
                webSocketFactory = newClassInstance(WebSocketFactory.class, DefaultWebSocketFactory.class);
            } catch (InstantiationException e) {
                logger.error("", e);
            } catch (IllegalAccessException e) {
                logger.error("", e);
            }
        }
        return this;
    }

    public MetaBroadcaster metaBroadcaster() {
        return metaBroadcaster;
    }

    private AtmosphereFramework configureMetaBroadcaster() {
        try {
            metaBroadcaster = newClassInstance(MetaBroadcaster.class, DefaultMetaBroadcaster.class);
            metaBroadcaster.configure(config);
        } catch (InstantiationException e) {
            logger.error("", e);
        } catch (IllegalAccessException e) {
            logger.error("", e);
        }
        return this;
    }

    /**
     * Get the default {@link org.atmosphere.cpr.Serializer} class name to use for {@link org.atmosphere.cpr.AtmosphereResource}s.
     *
     * @return the class name as a string, might be null if not configured
     */
    public String getDefaultSerializerClassName() {
        return defaultSerializerClassName;
    }

    /**
     * Get the default {@link org.atmosphere.cpr.Serializer} class to use for {@link org.atmosphere.cpr.AtmosphereResource}s.
     *
     * @return the class, might be null if not configured
     */
    public Class<Serializer> getDefaultSerializerClass() {
        return defaultSerializerClass;
    }

    /**
     * Set the default {@link org.atmosphere.cpr.Serializer} class name to use for {@link org.atmosphere.cpr.AtmosphereResource}s.
     *
     * @param defaultSerializerClassName the class name to use
     * @return this
     */
    public AtmosphereFramework setDefaultSerializerClassName(String defaultSerializerClassName) {
        this.defaultSerializerClassName = defaultSerializerClassName;
        initDefaultSerializer();
        return this;
    }

    private void initDefaultSerializer() {
        if (defaultSerializerClassName != null && !defaultSerializerClassName.isEmpty()) {
            try {
                @SuppressWarnings("unchecked")
                Class<Serializer> clazz = (Class<Serializer>) IOUtils.loadClass(Serializer.class, defaultSerializerClassName);
                if (Serializer.class.isAssignableFrom(clazz)) {
                    defaultSerializerClass = clazz;
                } else {
                    logger.error("Default Serializer class name does not implement Serializer interface");
                    defaultSerializerClassName = null;
                    defaultSerializerClass = null;
                }
            } catch (Exception e) {
                logger.error("Unable to set default Serializer", e);
                defaultSerializerClassName = null;
                defaultSerializerClass = null;
            }
        } else {
            defaultSerializerClassName = null;
            defaultSerializerClass = null;
        }
    }

    /**
     * Return the {@link AtmosphereResourceSessionFactory}
     *
     * @return the AtmosphereResourceSessionFactory
     */
    public synchronized AtmosphereResourceSessionFactory sessionFactory() {
        if (sessionFactory == null) {
            try {
                sessionFactory = newClassInstance(AtmosphereResourceSessionFactory.class, DefaultAtmosphereResourceSessionFactory.class);
            } catch (InstantiationException e) {
                logger.error("", e);
            } catch (IllegalAccessException e) {
                logger.error("", e);
            }
        }
        return sessionFactory;
    }

    /**
     * Return true is the {@link #destroy()} method has been invoked.
     *
     * @return true is the {@link #destroy()} method has been invoked.
     */
    public boolean isDestroyed() {
        return isDestroyed.get();
    }

    /**
     * Add a {@link org.atmosphere.cpr.AtmosphereFrameworkListener}
     *
     * @param l {@link org.atmosphere.cpr.AtmosphereFrameworkListener}
     * @return this;
     */
    public AtmosphereFramework frameworkListener(AtmosphereFrameworkListener l) {
        frameworkListeners.add(l);
        return this;
    }

    /**
     * Return the list of {@link org.atmosphere.cpr.AtmosphereFrameworkListener}
     *
     * @return {@link org.atmosphere.cpr.AtmosphereFrameworkListener}
     */
    public List<AtmosphereFrameworkListener> frameworkListeners() {
        return frameworkListeners;
    }

    protected void onPreInit() {
        for (AtmosphereFrameworkListener l : frameworkListeners) {
            try {
                l.onPreInit(this);
            } catch (Exception e) {
                logger.error("", e);
            }
        }
    }

    protected void onPostInit() {
        for (AtmosphereFrameworkListener l : frameworkListeners) {
            try {
                l.onPostInit(this);
            } catch (Exception e) {
                logger.error("", e);
            }
        }
    }

    protected void onPreDestroy() {
        for (AtmosphereFrameworkListener l : frameworkListeners) {
            try {
                l.onPreDestroy(this);
            } catch (Exception e) {
                logger.error("", e);
            }
        }
    }

    protected void onPostDestroy() {
        for (AtmosphereFrameworkListener l : frameworkListeners) {
            try {
                l.onPostDestroy(this);
            } catch (Exception e) {
                logger.error("", e);
            }
        }
    }

    /**
     * Return the list of {@link org.atmosphere.cpr.AtmosphereResourceListener}
     *
     * @return the list of {@link org.atmosphere.cpr.AtmosphereResourceListener}
     */
    public List<AtmosphereResourceListener> atmosphereResourceListeners() {
        return atmosphereResourceListeners;
    }

    /**
     * Add a {@link org.atmosphere.cpr.AtmosphereResourceListener}
     *
     * @param atmosphereResourceListener a {@link org.atmosphere.cpr.AtmosphereResourceListener}
     * @return this
     */
    public AtmosphereFramework atmosphereResourceListener(AtmosphereResourceListener atmosphereResourceListener) {
        atmosphereResourceListeners.add(atmosphereResourceListener);
        return this;
    }

    /**
     * Set a {@link java.util.UUID} like implementation for generating random UUID String
     *
     * @param uuidProvider
     * @return this
     */
    public AtmosphereFramework uuidProvider(UUIDProvider uuidProvider) {
        this.uuidProvider = uuidProvider;
        return this;
    }

    /**
     * Return the {@link org.atmosphere.util.UUIDProvider}
     *
     * @return {@link org.atmosphere.util.UUIDProvider}
     */
    public UUIDProvider uuidProvider() {
        return uuidProvider;
    }

    /**
     * Return the {@link WebSocketFactory}
     *
     * @return the {@link WebSocketFactory}
     */
    public WebSocketFactory webSocketFactory() {
        if (webSocketFactory == null) {
            configureWebSocketFactory();
        }
        return webSocketFactory;
    }

    /**
     * Configure the {@link WebSocketFactory}
     *
     * @param webSocketFactory the {@link WebSocketFactory}
     * @return this
     */
    public AtmosphereFramework webSocketFactory(WebSocketFactory webSocketFactory) {
        this.webSocketFactory = webSocketFactory;
        return this;
    }

    /**
     * If a {@link ContainerInitializer} fail, log the excetion here.
     * @param initializationError
     */
    public void initializationError(IllegalStateException initializationError) {
        this.initializationError = initializationError;
    }
}
