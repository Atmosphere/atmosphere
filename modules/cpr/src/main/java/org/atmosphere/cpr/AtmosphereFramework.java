/*
 * Copyright 2013 Jeanfrancois Arcand
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
import org.atmosphere.cache.UUIDBroadcasterCache;
import org.atmosphere.config.ApplicationConfiguration;
import org.atmosphere.config.AtmosphereHandlerConfig;
import org.atmosphere.config.AtmosphereHandlerProperty;
import org.atmosphere.config.FrameworkConfiguration;
import org.atmosphere.container.BlockingIOCometSupport;
import org.atmosphere.container.Tomcat7BIOSupportWithWebSocket;
import org.atmosphere.di.InjectorProvider;
import org.atmosphere.di.ServletContextHolder;
import org.atmosphere.di.ServletContextProvider;
import org.atmosphere.handler.AbstractReflectorAtmosphereHandler;
import org.atmosphere.handler.ReflectorServletProcessor;
import org.atmosphere.interceptor.AndroidAtmosphereInterceptor;
import org.atmosphere.interceptor.DefaultHeadersInterceptor;
import org.atmosphere.interceptor.InvokationOrder;
import org.atmosphere.interceptor.JSONPAtmosphereInterceptor;
import org.atmosphere.interceptor.JavaScriptProtocol;
import org.atmosphere.interceptor.OnDisconnectInterceptor;
import org.atmosphere.interceptor.PaddingAtmosphereInterceptor;
import org.atmosphere.interceptor.SSEAtmosphereInterceptor;
import org.atmosphere.util.AtmosphereConfigReader;
import org.atmosphere.util.DefaultEndpointMapper;
import org.atmosphere.util.EndpointMapper;
import org.atmosphere.util.IntrospectionUtils;
import org.atmosphere.util.ServletProxyFactory;
import org.atmosphere.util.Version;
import org.atmosphere.util.analytics.FocusPoint;
import org.atmosphere.util.analytics.JGoogleAnalyticsTracker;
import org.atmosphere.util.analytics.ModuleDetection;
import org.atmosphere.websocket.DefaultWebSocketProcessor;
import org.atmosphere.websocket.WebSocket;
import org.atmosphere.websocket.WebSocketHandler;
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
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
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
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.atmosphere.cpr.ApplicationConfig.ALLOW_QUERYSTRING_AS_REQUEST;
import static org.atmosphere.cpr.ApplicationConfig.ATMOSPHERE_HANDLER;
import static org.atmosphere.cpr.ApplicationConfig.ATMOSPHERE_HANDLER_MAPPING;
import static org.atmosphere.cpr.ApplicationConfig.ATMOSPHERE_HANDLER_PATH;
import static org.atmosphere.cpr.ApplicationConfig.BROADCASTER_CACHE;
import static org.atmosphere.cpr.ApplicationConfig.BROADCASTER_CLASS;
import static org.atmosphere.cpr.ApplicationConfig.BROADCASTER_FACTORY;
import static org.atmosphere.cpr.ApplicationConfig.BROADCASTER_LIFECYCLE_POLICY;
import static org.atmosphere.cpr.ApplicationConfig.BROADCAST_FILTER_CLASSES;
import static org.atmosphere.cpr.ApplicationConfig.DISABLE_ONSTATE_EVENT;
import static org.atmosphere.cpr.ApplicationConfig.PROPERTY_ATMOSPHERE_XML;
import static org.atmosphere.cpr.ApplicationConfig.PROPERTY_BLOCKING_COMETSUPPORT;
import static org.atmosphere.cpr.ApplicationConfig.PROPERTY_COMET_SUPPORT;
import static org.atmosphere.cpr.ApplicationConfig.PROPERTY_NATIVE_COMETSUPPORT;
import static org.atmosphere.cpr.ApplicationConfig.PROPERTY_SERVLET_MAPPING;
import static org.atmosphere.cpr.ApplicationConfig.PROPERTY_SESSION_SUPPORT;
import static org.atmosphere.cpr.ApplicationConfig.PROPERTY_THROW_EXCEPTION_ON_CLONED_REQUEST;
import static org.atmosphere.cpr.ApplicationConfig.PROPERTY_USE_STREAM;
import static org.atmosphere.cpr.ApplicationConfig.RESUME_AND_KEEPALIVE;
import static org.atmosphere.cpr.ApplicationConfig.SUSPENDED_ATMOSPHERE_RESOURCE_UUID;
import static org.atmosphere.cpr.ApplicationConfig.WEBSOCKET_PROCESSOR;
import static org.atmosphere.cpr.ApplicationConfig.WEBSOCKET_PROTOCOL;
import static org.atmosphere.cpr.ApplicationConfig.WEBSOCKET_SUPPORT;
import static org.atmosphere.cpr.FrameworkConfig.ATMOSPHERE_CONFIG;
import static org.atmosphere.cpr.FrameworkConfig.HAZELCAST_BROADCASTER;
import static org.atmosphere.cpr.FrameworkConfig.JERSEY_BROADCASTER;
import static org.atmosphere.cpr.FrameworkConfig.JERSEY_CONTAINER;
import static org.atmosphere.cpr.FrameworkConfig.JGROUPS_BROADCASTER;
import static org.atmosphere.cpr.FrameworkConfig.JMS_BROADCASTER;
import static org.atmosphere.cpr.FrameworkConfig.RABBITMQ_BROADCASTER;
import static org.atmosphere.cpr.FrameworkConfig.REDIS_BROADCASTER;
import static org.atmosphere.cpr.FrameworkConfig.RMI_BROADCASTER;
import static org.atmosphere.cpr.FrameworkConfig.XMPP_BROADCASTER;
import static org.atmosphere.cpr.HeaderConfig.ATMOSPHERE_POST_BODY;
import static org.atmosphere.cpr.HeaderConfig.X_ATMOSPHERE_TRACKING_ID;
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
public class AtmosphereFramework implements ServletContextProvider {
    public static final String DEFAULT_ATMOSPHERE_CONFIG_PATH = "/META-INF/atmosphere.xml";
    public static final String DEFAULT_LIB_PATH = "/WEB-INF/lib/";
    public static final String DEFAULT_HANDLER_PATH = "/WEB-INF/classes/";
    public static final String MAPPING_REGEX = "[a-zA-Z0-9-&.*_~=@;\\?]+";
    public static final String ROOT = "/*";

    protected static final Logger logger = LoggerFactory.getLogger(AtmosphereFramework.class);

    protected final List<String> broadcasterFilters = new ArrayList<String>();
    protected final List<AsyncSupportListener> asyncSupportListeners = new ArrayList<AsyncSupportListener>();
    protected final ArrayList<String> possibleComponentsCandidate = new ArrayList<String>();
    protected final HashMap<String, String> initParams = new HashMap<String, String>();
    protected final AtmosphereConfig config;
    protected final AtomicBoolean isCometSupportConfigured = new AtomicBoolean(false);
    protected final boolean isFilter;
    protected final Map<String, AtmosphereHandlerWrapper> atmosphereHandlers = new ConcurrentHashMap<String, AtmosphereHandlerWrapper>();
    protected final ConcurrentLinkedQueue<String> broadcasterTypes = new ConcurrentLinkedQueue<String>();
    protected final ConcurrentLinkedQueue<BroadcasterCacheInspector> inspectors = new ConcurrentLinkedQueue<BroadcasterCacheInspector>();

    protected String mappingRegex = MAPPING_REGEX;
    protected boolean useNativeImplementation = false;
    protected boolean useBlockingImplementation = false;
    protected boolean useStreamForFlushingComments = true;
    protected boolean useServlet30 = true;
    protected AsyncSupport asyncSupport;
    protected String broadcasterClassName = DefaultBroadcaster.class.getName();
    protected boolean isCometSupportSpecified = false;
    protected boolean isBroadcasterSpecified = false;
    protected boolean isSessionSupportSpecified = false;
    protected boolean isThrowExceptionOnClonedRequestSpecified = false;
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
    private boolean hasNewWebSocketProtocol = false;
    protected String atmosphereDotXmlPath = DEFAULT_ATMOSPHERE_CONFIG_PATH;
    protected final LinkedList<AtmosphereInterceptor> interceptors = new LinkedList<AtmosphereInterceptor>();
    protected boolean scanDone = false;
    protected String annotationProcessorClassName = "org.atmosphere.cpr.DefaultAnnotationProcessor";
    protected final List<BroadcasterListener> broadcasterListeners = new ArrayList<BroadcasterListener>();
    protected String webSocketProcessorClassName = DefaultWebSocketProcessor.class.getName();
    protected boolean webSocketProtocolInitialized = false;
    protected EndpointMapper<AtmosphereHandlerWrapper> endpointMapper = new DefaultEndpointMapper<AtmosphereHandlerWrapper>();
    protected String libPath = DEFAULT_LIB_PATH;
    protected boolean isInit;
    protected boolean sharedThreadPools = true;
    protected final List<String> packages = new ArrayList<String>();
    protected final LinkedList<String> annotationPackages = new LinkedList<String>();
    protected boolean allowAllClassesScan = true;
    protected boolean annotationFound = false;
    protected boolean executeFirstSet = false;

    protected final Class<? extends AtmosphereInterceptor>[] defaultInterceptors = new Class[]{
            // Default Interceptor
            DefaultHeadersInterceptor.class,
            // WebKit & IE Padding
            PaddingAtmosphereInterceptor.class,
            // Android 2.3.x streaming support
            AndroidAtmosphereInterceptor.class,
            // Add SSE support
            SSEAtmosphereInterceptor.class,
            // ADD JSONP support
            JSONPAtmosphereInterceptor.class,
            // ADD Tracking ID Handshake
            JavaScriptProtocol.class,
            // OnDisconnect
            OnDisconnectInterceptor.class
    };

    /**
     * An implementation of {@link AbstractReflectorAtmosphereHandler}
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

    public static final class AtmosphereHandlerWrapper {

        public final AtmosphereHandler atmosphereHandler;
        public Broadcaster broadcaster;
        public String mapping;
        public List<AtmosphereInterceptor> interceptors = Collections.emptyList();
        public boolean create;

        public AtmosphereHandlerWrapper(BroadcasterFactory broadcasterFactory, AtmosphereHandler atmosphereHandler, String mapping) {
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
        }

        public AtmosphereHandlerWrapper(AtmosphereHandler atmosphereHandler, Broadcaster broadcaster) {
            this.atmosphereHandler = atmosphereHandler;
            this.broadcaster = broadcaster;
        }

        @Override
        public String toString() {
            return "AtmosphereHandlerWrapper{ atmosphereHandler=" + atmosphereHandler + ", broadcaster=" +
                    broadcaster + " }";
        }
    }

    /**
     * Create an AtmosphereFramework.
     */
    public AtmosphereFramework() {
        this(false, true);
    }

    /**
     * Create an AtmosphereFramework and initialize it via {@link AtmosphereFramework#init(javax.servlet.ServletConfig)}
     */
    public AtmosphereFramework(ServletConfig sc) throws ServletException {
        this(false, true);
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
        readSystemProperties();
        populateBroadcasterType();
        config = new AtmosphereConfig(this);
    }

    /**
     * The order of addition is quite important here.
     */
    private void populateBroadcasterType() {
        broadcasterTypes.add(HAZELCAST_BROADCASTER);
        broadcasterTypes.add(XMPP_BROADCASTER);
        broadcasterTypes.add(REDIS_BROADCASTER);
        broadcasterTypes.add(JGROUPS_BROADCASTER);
        broadcasterTypes.add(JMS_BROADCASTER);
        broadcasterTypes.add(RMI_BROADCASTER);
        broadcasterTypes.add(RABBITMQ_BROADCASTER);
    }

    /**
     * Add an {@link AtmosphereHandler} serviced by the {@link Servlet}
     * This API is exposed to allow embedding an Atmosphere application.
     *
     * @param mapping The servlet mapping (servlet path)
     * @param h       implementation of an {@link AtmosphereHandler}
     * @param l       An array of {@link AtmosphereInterceptor}
     */
    public AtmosphereFramework addAtmosphereHandler(String mapping, AtmosphereHandler h, List<AtmosphereInterceptor> l) {
        if (!mapping.startsWith("/")) {
            mapping = "/" + mapping;
        }
        AtmosphereHandlerWrapper w = new AtmosphereHandlerWrapper(broadcasterFactory, h, mapping);
        w.interceptors = l;
        addMapping(mapping, w);
        logger.info("Installed AtmosphereHandler {} mapped to context-path: {}", h.getClass().getName(), mapping);
        logger.info("Installed the following AtmosphereInterceptor mapped to AtmosphereHandler {}", h.getClass().getName());
        if (l.size() > 0) {
            for (AtmosphereInterceptor s : l) {
                logger.info("\t{} : {}", s.getClass().getSimpleName(), s);
            }
        }
        return this;
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
        // We are using JAXRS mapping algorithm.
        if (path.contains("*")) {
            path = path.replace("*", mappingRegex);
        }

        if (path.endsWith("/")) {
            path = path + mappingRegex;
        }

        InjectorProvider.getInjector().inject(w.atmosphereHandler);
        atmosphereHandlers.put(path, w);
        return this;
    }

    /**
     * Add an {@link AtmosphereHandler} serviced by the {@link Servlet}
     * This API is exposed to allow embedding an Atmosphere application.
     *
     * @param mapping       The servlet mapping (servlet path)
     * @param h             implementation of an {@link AtmosphereHandler}
     * @param broadcasterId The {@link Broadcaster#getID} value.
     * @param l             An attay of {@link AtmosphereInterceptor}
     */
    public AtmosphereFramework addAtmosphereHandler(String mapping, AtmosphereHandler h, String broadcasterId, List<AtmosphereInterceptor> l) {
        if (!mapping.startsWith("/")) {
            mapping = "/" + mapping;
        }

        AtmosphereHandlerWrapper w = new AtmosphereHandlerWrapper(broadcasterFactory, h, mapping);
        w.broadcaster.setID(broadcasterId);
        w.interceptors = l;
        addMapping(mapping, w);
        logger.info("Installed AtmosphereHandler {} mapped to context-path: {}", h.getClass().getName(), mapping);
        if (l.size() > 0) {
            logger.info("Installed AtmosphereInterceptor {} mapped to AtmosphereHandler {}", l, h.getClass().getName());
        }
        return this;
    }

    /**
     * Add an {@link AtmosphereHandler} serviced by the {@link Servlet}
     * This API is exposed to allow embedding an Atmosphere application.
     *
     * @param mapping       The servlet mapping (servlet path)
     * @param h             implementation of an {@link AtmosphereHandler}
     * @param broadcasterId The {@link Broadcaster#getID} value.
     */
    public AtmosphereFramework addAtmosphereHandler(String mapping, AtmosphereHandler h, String broadcasterId) {
        addAtmosphereHandler(mapping, h, broadcasterId, Collections.<AtmosphereInterceptor>emptyList());
        return this;
    }

    /**
     * Add an {@link AtmosphereHandler} serviced by the {@link Servlet}
     * This API is exposed to allow embedding an Atmosphere application.
     *
     * @param mapping     The servlet mapping (servlet path)
     * @param h           implementation of an {@link AtmosphereHandler}
     * @param broadcaster The {@link Broadcaster} associated with AtmosphereHandler.
     * @param l           An attay of {@link AtmosphereInterceptor}
     */
    public AtmosphereFramework addAtmosphereHandler(String mapping, AtmosphereHandler h, Broadcaster broadcaster, List<AtmosphereInterceptor> l) {
        if (!mapping.startsWith("/")) {
            mapping = "/" + mapping;
        }

        AtmosphereHandlerWrapper w = new AtmosphereHandlerWrapper(h, broadcaster);
        w.interceptors = l;

        addMapping(mapping, w);
        logger.info("Installed AtmosphereHandler {} mapped to context-path: {}", h.getClass().getName(), mapping);
        if (l.size() > 0) {
            logger.info("Installed AtmosphereInterceptor {} mapped to AtmosphereHandler {}", l, h.getClass().getName());
        }
        return this;
    }

    /**
     * Add an {@link AtmosphereHandler} serviced by the {@link Servlet}
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
     * Remove an {@link AtmosphereHandler}
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
     * Remove all {@link AtmosphereHandler}
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
     * Initialize the AtmosphereFramework. Invoke that method after having properly configured this class using the setter.
     */
    public AtmosphereFramework init() {
        try {
            init(new ServletConfig() {

                @Override
                public String getServletName() {
                    return "AtmosphereFramework";
                }

                @Override
                public ServletContext getServletContext() {
                    return (ServletContext) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{ServletContext.class},
                            new InvocationHandler() {
                                @Override
                                public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                                    return ServletProxyFactory.getDefault().proxy(proxy, method, args);
                                }
                            });
                }

                @Override
                public String getInitParameter(String name) {
                    return initParams.get(name);
                }

                @Override
                public Enumeration<String> getInitParameterNames() {
                    return Collections.enumeration(initParams.values());
                }
            }, false);
        } catch (ServletException e) {
            logger.error("", e);
        }
        return this;
    }

    /**
     * Initialize the AtmosphereFramework using the {@link ServletContext}
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
     * Initialize the AtmosphereFramework using the {@link ServletContext}
     *
     * @param sc the {@link ServletContext}
     */
    public AtmosphereFramework init(final ServletConfig sc, boolean wrap) throws ServletException {
        if (isInit) return this;
        try {
            ServletContextHolder.register(this);

            ServletConfig scFacade;

            if (wrap) {
                scFacade = new ServletConfig() {

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
                            return sc.getInitParameter(name);
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
                scFacade = sc;
            }
            this.servletConfig = scFacade;

            preventOOM();
            doInitParams(scFacade);
            doInitParamsForWebSocket(scFacade);
            asyncSupportListener(new AsyncSupportListenerAdapter());

            configureAnnotationPackages();

            configureBroadcasterFactory();
            configureScanningPackage(scFacade);
            installAnnotationProcessor(scFacade);

            autoConfigureService(scFacade.getServletContext());

            // Reconfigure in case an annotation changed the default.
            configureBroadcasterFactory();
            patchContainer();
            configureBroadcaster();
            loadConfiguration(scFacade);
            initWebSocket();
            initEndpointMapper();

            autoDetectContainer();
            configureWebDotXmlAtmosphereHandler(scFacade);
            asyncSupport.init(scFacade);
            initAtmosphereHandler(scFacade);
            configureAtmosphereInterceptor(scFacade);
            analytics();

            if (broadcasterCacheClassName == null) {
                logger.warn("No BroadcasterCache configured. Broadcasted message between client reconnection will be LOST. " +
                        "It is recommended to configure the {}", UUIDBroadcasterCache.class.getName());
            } else {
                logger.info("Using BroadcasterCache: {}", broadcasterCacheClassName);
            }

            // http://java.net/jira/browse/ATMOSPHERE-157
            if (sc.getServletContext() != null) {
                sc.getServletContext().setAttribute(BroadcasterFactory.class.getName(), broadcasterFactory);
            }

            for (String i : broadcasterFilters) {
                logger.info("Using BroadcastFilter: {}", i);
            }

            String s = config.getInitParameter(ApplicationConfig.BROADCASTER_SHARABLE_THREAD_POOLS);
            if (s != null) {
                sharedThreadPools = Boolean.parseBoolean(s);
            }

            logger.info("Shared ExecutorService supported: {}", sharedThreadPools);
            logger.info("HttpSession supported: {}", config.isSupportSession());
            logger.info("Using BroadcasterFactory: {}", broadcasterFactory.getClass().getName());
            logger.info("Using WebSocketProcessor: {}", webSocketProcessorClassName);
            logger.info("Using Broadcaster: {}", broadcasterClassName);
            logger.info("Atmosphere Framework {} started.", Version.getRawVersion());

            logger.info("\n\n\tFor Atmosphere Framework Commercial Support, visit \n\t{} " +
                    "or send an email to {}\n", "http://www.async-io.org/", "support@async-io.org");
        } catch (Throwable t) {
            logger.error("Failed to initialize Atmosphere Framework", t);

            if (t instanceof ServletException) {
                throw (ServletException) t;
            }

            throw new ServletException(t);
        }
        isInit = true;
        return this;
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
        final String container = getServletContext().getServerInfo();
        if (allowAllClassesScan) {
            Thread t = new Thread() {
                public void run() {
                    try {
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
                        try {
                            while ((inputLine = in.readLine().trim()) != null) {
                                if (inputLine.startsWith("ATMO_VERSION=")) {
                                    newVersion = inputLine.substring("ATMO_VERSION=".length());
                                    break;
                                }
                            }
                        } finally {
                            if (newVersion.compareTo(Version.getRawVersion()) != 0) {
                                logger.info("\n\n\tCurrent version of Atmosphere {} \n\tNewest version of Atmosphere available {}\n\n", Version.getRawVersion(), newVersion);
                            }
                            try {
                                in.close();
                            } catch (IOException ex) {
                            }
                            urlConnection.disconnect();
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
                    AtmosphereInterceptor ai = (AtmosphereInterceptor) Thread.currentThread().getContextClassLoader()
                            .loadClass(a.trim()).newInstance();
                    interceptor(ai);
                } catch (InstantiationException e) {
                    logger.warn("", e);
                } catch (IllegalAccessException e) {
                    logger.warn("", e);
                } catch (ClassNotFoundException e) {
                    logger.warn("", e);
                }
            }
        }

        logger.info("Installing Default AtmosphereInterceptor");
        s = sc.getInitParameter(ApplicationConfig.DISABLE_ATMOSPHEREINTERCEPTOR);
        if (s == null) {
            for (Class<? extends AtmosphereInterceptor> a : defaultInterceptors) {
                interceptors.addLast(newAInterceptor(a));
            }
            logger.info("Set {} to disable them.", ApplicationConfig.DISABLE_ATMOSPHEREINTERCEPTOR, interceptors);
        }
        initInterceptors();
    }

    protected AtmosphereInterceptor newAInterceptor(Class<? extends AtmosphereInterceptor> a) {
        AtmosphereInterceptor ai = null;
        try {
            ai = (AtmosphereInterceptor) getClass().getClassLoader().loadClass(a.getName()).newInstance();
            logger.info("\t{} : {}", a.getName(), ai);
        } catch (Exception ex) {
            logger.warn("", ex);
        }
        return ai;
    }

    protected void initInterceptors() {
        for (AtmosphereInterceptor i : interceptors) {
            i.configure(config);
        }

        for (AtmosphereHandlerWrapper w : atmosphereHandlers.values()) {
            List<AtmosphereInterceptor> remove = new ArrayList<AtmosphereInterceptor>();
            if (w.interceptors != null) {
                for (AtmosphereInterceptor i : w.interceptors) {

                    //
                    InvokationOrder.PRIORITY p = InvokationOrder.class.isAssignableFrom(i.getClass()) ?
                            InvokationOrder.class.cast(i).priority() : InvokationOrder.AFTER_DEFAULT;

                    // We need to relocate the interceptor
                    if (!p.equals(InvokationOrder.AFTER_DEFAULT)) {
                        positionInterceptor(p, i);
                        remove.add(i);
                    }
                    i.configure(config);
                }

                for (AtmosphereInterceptor i : remove) {
                    w.interceptors.remove(i);
                }

            }
        }
    }

    protected void configureWebDotXmlAtmosphereHandler(ServletConfig sc) {
        String s = sc.getInitParameter(ATMOSPHERE_HANDLER);
        if (s != null) {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            try {

                String mapping = sc.getInitParameter(ATMOSPHERE_HANDLER_MAPPING);
                if (mapping == null) {
                    mapping = ROOT;
                }
                addAtmosphereHandler(mapping, (AtmosphereHandler) cl.loadClass(s).newInstance());
            } catch (Exception ex) {
                logger.warn("Unable to load WebSocketHandle instance", ex);
            }
        }
    }

    protected void configureScanningPackage(ServletConfig sc) {
        String s = sc.getInitParameter(ApplicationConfig.ANNOTATION_PACKAGE);
        if (s != null) {
            String[] list = s.split(",");
            for (String a : list) {
                packages.add(a);
            }
        }
    }

    public void configureBroadcasterFactory() {
        try {
            // Check auto supported one
            if (isBroadcasterSpecified == false) {
                broadcasterClassName = lookupDefaultBroadcasterType(broadcasterClassName);
            }

            if (broadcasterFactoryClassName != null) {
                broadcasterFactory = (BroadcasterFactory) Thread.currentThread().getContextClassLoader()
                        .loadClass(broadcasterFactoryClassName).newInstance();
            }

            if (broadcasterFactory == null) {
                Class<? extends Broadcaster> bc =
                        (Class<? extends Broadcaster>) Thread.currentThread().getContextClassLoader()
                                .loadClass(broadcasterClassName);
                broadcasterFactory = new DefaultBroadcasterFactory(bc, broadcasterLifeCyclePolicy, config);
            }

            for (BroadcasterListener b : broadcasterListeners) {
                broadcasterFactory.addBroadcasterListener(b);
            }

            BroadcasterFactory.setBroadcasterFactory(broadcasterFactory, config);
            InjectorProvider.getInjector().inject(broadcasterFactory);
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
                    if (broadcasterCacheClassName != null) {
                        BroadcasterCache cache = (BroadcasterCache) Thread.currentThread().getContextClassLoader()
                                .loadClass(broadcasterCacheClassName).newInstance();
                        InjectorProvider.getInjector().inject(cache);
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
     * Read init param from web.xml and apply them.
     *
     * @param sc {@link ServletConfig}
     */
    protected void doInitParams(ServletConfig sc) {
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
        if (s != null) {
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
        if (s != null) {
            config.setSupportSession(Boolean.valueOf(s));
            if (sc.getServletContext().getMajorVersion() >= 3) {
                try {
                    sc.getServletContext().addListener(SessionSupport.class);
                } catch (Throwable t) {
                    logger.warn("SessionSupport error. Make sure you define {} as a listener in web.xml instead", SessionSupport.class.getName(), t);
                }
            } else {
                logger.debug("Make sure you define {} as a listener in web.xml", SessionSupport.class.getName());
            }
            isSessionSupportSpecified = true;
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
        s = sc.getInitParameter(RESUME_AND_KEEPALIVE);
        if (s != null) {
            initParams.put(RESUME_AND_KEEPALIVE, s);
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
        s = sc.getInitParameter(ApplicationConfig.HANDLER_MAPPING_REGEX);
        if (s != null) {
            mappingRegex = s;
        }

        s = sc.getInitParameter(FrameworkConfig.JERSEY_SCANNING_PACKAGE);
        if (s != null) {
            packages.add(s);
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

            if (atmosphereHandlers.size() == 0) {
                autoDetectAtmosphereHandlers(sc.getServletContext(), urlC);

                if (atmosphereHandlers.size() == 0) {
                    detectSupportedFramework(sc);
                }
            }

            autoDetectWebSocketHandler(sc.getServletContext(), urlC);
        } catch (Throwable t) {
            throw new ServletException(t);
        }
    }

    /**
     * Auto-detect Jersey when no atmosphere.xml file are specified.
     *
     * @param sc {@link ServletConfig}
     * @return true if Jersey classes are detected
     * @throws ClassNotFoundException
     */
    protected boolean detectSupportedFramework(ServletConfig sc) throws ClassNotFoundException, IllegalAccessException,
            InstantiationException, NoSuchMethodException, InvocationTargetException {

        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        String broadcasterClassNameTmp = null;

        try {
            cl.loadClass(JERSEY_CONTAINER);

            if (!isBroadcasterSpecified) {
                broadcasterClassNameTmp = lookupDefaultBroadcasterType(JERSEY_BROADCASTER);

                cl.loadClass(broadcasterClassNameTmp);
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

        logger.warn("Missing META-INF/atmosphere.xml but found the Jersey runtime. Starting Jersey");

        // Atmosphere 1.1 : could add regressions
        // Jersey will handle itself the headers.
        //initParams.put(WRITE_HEADERS, "false");

        ReflectorServletProcessor rsp = new ReflectorServletProcessor();
        if (broadcasterClassNameTmp != null) broadcasterClassName = broadcasterClassNameTmp;
        rsp.setServletClassName(JERSEY_CONTAINER);
        sessionSupport(false);
        initParams.put(DISABLE_ONSTATE_EVENT, "true");

        String mapping = sc.getInitParameter(PROPERTY_SERVLET_MAPPING);
        if (mapping == null) {
            mapping = sc.getInitParameter(ATMOSPHERE_HANDLER_MAPPING);
            if (mapping == null) {
                mapping = ROOT;
            }
        }
        Class<? extends Broadcaster> bc = (Class<? extends Broadcaster>) cl.loadClass(broadcasterClassName);

        broadcasterFactory.destroy();

        broadcasterFactory = new DefaultBroadcasterFactory(bc, broadcasterLifeCyclePolicy, config);
        BroadcasterFactory.setBroadcasterFactory(broadcasterFactory, config);
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

    protected String lookupDefaultBroadcasterType(String defaultB) {
        for (String b : broadcasterTypes) {
            try {
                Class.forName(b);
                return b;
            } catch (ClassNotFoundException e) {
            }
        }

        return defaultB;
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
     * Initialize {@link AtmosphereServletProcessor}
     *
     * @param sc the {@link ServletConfig}
     * @throws javax.servlet.ServletException
     */
    public void initAtmosphereHandler(ServletConfig sc) throws ServletException {
        AtmosphereHandler a;
        AtmosphereHandlerWrapper w;
        for (Entry<String, AtmosphereHandlerWrapper> h : atmosphereHandlers.entrySet()) {
            w = h.getValue();
            a = w.atmosphereHandler;
            if (a instanceof AtmosphereServletProcessor) {
                ((AtmosphereServletProcessor) a).init(sc);
            }
        }

        if (atmosphereHandlers.size() == 0 && !SimpleHttpProtocol.class.isAssignableFrom(webSocketProtocol.getClass())) {
            logger.debug("Adding a void AtmosphereHandler mapped to /* to allow WebSocket application only");
            addAtmosphereHandler(ROOT, new AbstractReflectorAtmosphereHandler() {
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
                webSocketProtocol = (WebSocketProtocol) Thread.currentThread().getContextClassLoader()
                        .loadClass(webSocketProtocolClassName).newInstance();
                logger.info("Installed WebSocketProtocol {} ", webSocketProtocolClassName);
            } catch (Exception ex) {
                try {
                    webSocketProtocol = (WebSocketProtocol) AtmosphereFramework.class.getClassLoader()
                            .loadClass(webSocketProtocolClassName).newInstance();
                    logger.info("Installed WebSocketProtocol {} ", webSocketProtocolClassName);
                } catch (Exception ex2) {
                    logger.error("Cannot load the WebSocketProtocol {}", getWebSocketProtocolClassName(), ex);
                    webSocketProtocol = new SimpleHttpProtocol();
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
                endpointMapper = (EndpointMapper) AtmosphereFramework.class.getClassLoader()
                        .loadClass(s).newInstance();
                logger.info("Installed EndpointMapper {} ", s);
            } catch (Exception ex) {
                logger.error("Cannot load the EndpointMapper {}", s, ex);
            }
        }
    }

    public AtmosphereFramework destroy() {
        if (asyncSupport != null && AsynchronousProcessor.class.isAssignableFrom(asyncSupport.getClass())) {
            ((AsynchronousProcessor) asyncSupport).shutdown();
        }

        // We just need one bc to shutdown the shared thread pool
        for (Entry<String, AtmosphereHandlerWrapper> entry : atmosphereHandlers.entrySet()) {
            AtmosphereHandlerWrapper handlerWrapper = entry.getValue();
            handlerWrapper.atmosphereHandler.destroy();
        }

        // Invoke ShutdownHook.
        config.destroy();

        BroadcasterFactory factory = broadcasterFactory;
        if (factory != null) {
            factory.destroy();
            BroadcasterFactory.factory = null;
        }

        WebSocketProcessorFactory.getDefault().destroy();
        return this;
    }

    /**
     * Load AtmosphereHandler defined under META-INF/atmosphere.xml
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
                        handler = (AtmosphereHandler) c.loadClass(atmoHandler.getClassName()).newInstance();
                    } else {
                        handler = new ReflectorServletProcessor();
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
                        broadcasterFactory = new DefaultBroadcasterFactory(bc, broadcasterLifeCyclePolicy, config);
                        BroadcasterFactory.setBroadcasterFactory(broadcasterFactory, config);
                    }

                    b = broadcasterFactory.lookup(atmoHandler.getContextRoot(), true);

                    AtmosphereHandlerWrapper wrapper = new AtmosphereHandlerWrapper(handler, b);
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

                    List<AtmosphereInterceptor> l = new ArrayList<AtmosphereInterceptor>();
                    if (atmoHandler.getAtmosphereInterceptorClasses() != null) {
                        for (String a : atmoHandler.getAtmosphereInterceptorClasses()) {
                            try {
                                AtmosphereInterceptor ai = (AtmosphereInterceptor) c.loadClass(a).newInstance();
                                l.add(ai);
                            } catch (Throwable e) {
                                logger.warn("", e);
                            }
                        }
                    }
                    wrapper.interceptors = l;
                    if (l.size() > 0) {
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
     * Set the {@link AsyncSupport} implementation. Make sure you don't set
     * an implementation that only works on some Container. See {@link BlockingIOCometSupport}
     * for an example.
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
     * Return the current {@link AsyncSupport}
     *
     * @return the current {@link AsyncSupport}
     */
    public AsyncSupport getAsyncSupport() {
        return asyncSupport;
    }

    /**
     * Return the current {@link AsyncSupport}
     *
     * @return the current {@link AsyncSupport}
     * @deprecated Use getAsyncSupport
     */
    public AsyncSupport getCometSupport() {
        return asyncSupport;
    }

    /**
     * Returns an instance of AsyncSupportResolver {@link AsyncSupportResolver}
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

        logger.info("Atmosphere is using async support: {} running under container: {}",
                getAsyncSupport().getClass().getName(), asyncSupport.getContainerName());
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
        if (atmosphereHandlers.size() > 0) return;

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

        if (file.isDirectory()) {
            getFiles(file);
            scanDone = true;

            for (String className : possibleComponentsCandidate) {
                try {
                    className = className.replace('\\', '/');
                    className = className.replaceFirst("^.*/(WEB-INF|target)(?:/scala-[^/]+)?/(test-)?classes/(.*)\\.class", "$3").replace("/", ".");
                    Class<?> clazz = classloader.loadClass(className);

                    if (AtmosphereHandler.class.isAssignableFrom(clazz)) {
                        AtmosphereHandler handler = (AtmosphereHandler) clazz.newInstance();
                        InjectorProvider.getInjector().inject(handler);
                        addMapping("/" + handler.getClass().getSimpleName(),
                                new AtmosphereHandlerWrapper(broadcasterFactory, handler, "/" + handler.getClass().getSimpleName()));
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

        String realPath = servletContext.getRealPath(handlersPath);

        // Weblogic bug
        if (realPath == null) {
            URL u = servletContext.getResource(handlersPath);
            if (u == null) return;
            realPath = u.getPath();
        }

        loadWebSocketFromPath(classloader, realPath);
    }

    protected void loadWebSocketFromPath(URLClassLoader classloader, String realPath) {
        File file = new File(realPath);

        if (file.isDirectory()) {
            getFiles(file);
            scanDone = true;

            for (String className : possibleComponentsCandidate) {
                try {
                    className = className.replace('\\', '/');
                    className = className.replaceFirst("^.*/(WEB-INF|target)(?:/scala-[^/]+)?/(test-)?classes/(.*)\\.class", "$3").replace("/", ".");
                    Class<?> clazz = classloader.loadClass(className);

                    if (WebSocketProtocol.class.isAssignableFrom(clazz)) {
                        webSocketProtocol = (WebSocketProtocol) clazz.newInstance();
                        InjectorProvider.getInjector().inject(webSocketProtocol);
                        logger.info("Installed WebSocketProtocol {}", webSocketProtocol);
                    }
                } catch (Throwable t) {
                    logger.trace("failed to load class as an WebSocketProtocol: " + className, t);
                }
            }
        }
    }


    /**
     * Get the list of possible candidate to load as {@link AtmosphereHandler}
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
     * Configure some Attribute on the {@link AtmosphereRequest}
     *
     * @param req {@link AtmosphereRequest}
     */
    public AtmosphereFramework configureRequestResponse(AtmosphereRequest req, AtmosphereResponse res) throws UnsupportedEncodingException {
        req.setAttribute(BROADCASTER_FACTORY, BroadcasterFactory.getDefault());
        req.setAttribute(PROPERTY_USE_STREAM, useStreamForFlushingComments);
        req.setAttribute(BROADCASTER_CLASS, broadcasterClassName);
        req.setAttribute(ATMOSPHERE_CONFIG, config);
        req.setAttribute(FrameworkConfig.THROW_EXCEPTION_ON_CLONED_REQUEST, config.isThrowExceptionOnCloned());
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
            s = UUID.randomUUID().toString();
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
     * Invoke the proprietary {@link AsyncSupport}
     *
     * @param req
     * @param res
     * @return an {@link Action}
     * @throws IOException
     * @throws ServletException
     */
    public Action doCometSupport(AtmosphereRequest req, AtmosphereResponse res) throws IOException, ServletException {
        Action a = null;
        try {
            configureRequestResponse(req, res);
            a = asyncSupport.service(req, res);
        } catch (IllegalStateException ex) {
            if (ex.getMessage() != null && (ex.getMessage().startsWith("Tomcat failed") || ex.getMessage().startsWith("JBoss failed"))) {
                if (!isFilter) {
                    logger.warn("Failed using comet support: {}, error: {} Is the Nio or Apr Connector enabled?", asyncSupport.getClass().getName(),
                            ex.getMessage());
                }
                logger.error("If you have more than one Connector enabled, make sure they both use the same protocol, " +
                        "e.g NIO/APR or HTTP for all. If not, {} will be used and cannot be changed.", BlockingIOCometSupport.class.getName());

                logger.trace(ex.getMessage(), ex);

                AsyncSupport current = asyncSupport;
                asyncSupport = asyncSupport.supportWebSocket() ? new Tomcat7BIOSupportWithWebSocket(config) : new BlockingIOCometSupport(config);
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

            if (req != null && a != null && a.type() != Action.TYPE.SUSPEND) {
                req.destroy();
                res.destroy();
                notify(Action.TYPE.DESTROYED, req, res);
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
     * Set the default {@link Broadcaster} class name
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
            w.broadcaster = broadcasterFactory.lookup(w.broadcaster.getID(), true);
        }
        return this;
    }

    /**
     * <tt>true</tt> if Atmosphere uses {@link AtmosphereResponse#getOutputStream()}
     * by default for write operation.
     *
     * @return the useStreamForFlushingComments
     */
    public boolean isUseStreamForFlushingComments() {
        return useStreamForFlushingComments;
    }

    /**
     * Set to <tt>true</tt> so Atmosphere uses {@link AtmosphereResponse#getOutputStream()}
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
     * {@link Broadcaster}
     *
     * @return {@link BroadcasterFactory}
     */
    public BroadcasterFactory getBroadcasterFactory() {
        return broadcasterFactory;
    }

    /**
     * Set the {@link BroadcasterFactory} which is used by Atmosphere to construct
     * {@link Broadcaster}
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
     * @return the {@link org.atmosphere.cpr.BroadcasterCache} class name.
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
     * Add a new Broadcaster class name AtmosphereServlet can use when initializing requests, and when
     * atmosphere.xml broadcaster element is unspecified.
     *
     * @param broadcasterTypeString
     */
    public AtmosphereFramework addBroadcasterType(String broadcasterTypeString) {
        broadcasterTypes.add(broadcasterTypeString);
        return this;
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
                    s = p.split("=");
                    if (s[0].equalsIgnoreCase("Content-Type")) {
                        // Use the one set by the user first.
                        if (request.getContentType() == null ||
                                !request.getContentType().equalsIgnoreCase(s.length > 1 ? s[1] : "")) {
                            request.contentType(s.length > 1 ? s[1] : "");
                        }
                    }
                    if (!s[0].toLowerCase().startsWith("x-atmosphere")
                            && !s[0].equalsIgnoreCase("x-cache-date")
                            && !s[0].equalsIgnoreCase("Content-Type")
                            && !s[0].equalsIgnoreCase("_")) {
                        q.append(s[0]).append("=").append(s.length > 1 ? s[1] : "").append("&");
                    }
                    headers.put(s[0], s.length > 1 ? s[1] : "");
                }
            }
        } catch (Exception ex) {
            logger.error("Unable to parse query string", ex);
        }
        if (q.length() > 0) q.deleteCharAt(q.length() - 1);
        request.queryString(q.toString());

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
     * Return the location of the jars containing the application classes. Default is WEB-INF/lib
     *
     * @return the location of the jars containing the application classes. Default is WEB-INF/lib
     */
    public String getLibPath() {
        return libPath;
    }

    /**
     * Set the location of the jars containing the application.
     *
     * @param libPath the location of the jars containing the application.
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
     * Set the {@link org.atmosphere.websocket.WebSocketProcessor} class name used to process WebSocket request. Default is
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
     * Add an {@link AtmosphereInterceptor} implementation. The adding order or {@link AtmosphereInterceptor} will be used, e.g
     * the first added {@link AtmosphereInterceptor} will always be called first.
     *
     * @param c {@link AtmosphereInterceptor}
     * @return this
     */
    public AtmosphereFramework interceptor(AtmosphereInterceptor c) {
        boolean found = false;
        for (AtmosphereInterceptor interceptor : interceptors) {
            if (interceptor.getClass().equals(c.getClass())) {
                found = true;
                break;
            }
        }

        if (!found) {
            InvokationOrder.PRIORITY p = InvokationOrder.class.isAssignableFrom(c.getClass()) ? InvokationOrder.class.cast(c).priority() : InvokationOrder.AFTER_DEFAULT;
            positionInterceptor(p, c);

            logger.info("Installed AtmosphereInterceptor {} with priority {} ", c, p.name());
        }
        return this;
    }

    protected void positionInterceptor(InvokationOrder.PRIORITY p, AtmosphereInterceptor c) {
        switch (p) {
            case AFTER_DEFAULT:
                interceptors.addLast(c);
                break;
            case BEFORE_DEFAULT:
                int pos = executeFirstSet ? 1 : 0;
                interceptors.add(pos, c);
                break;
            case FIRST_BEFORE_DEFAULT:
                if (executeFirstSet)
                    throw new IllegalStateException("Cannot set more than one AtmosphereInterceptor to be executed first");
                logger.info("AtmosphereInterceptor {} will always be executed first", c);
                interceptors.addFirst(c);
                executeFirstSet = true;
                break;
        }
    }

    /**
     * Return the list of {@link AtmosphereInterceptor}
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
     * Add an {@link AsyncSupportListener}
     *
     * @param asyncSupportListener an {@link AsyncSupportListener}
     * @return this;
     */
    public AtmosphereFramework asyncSupportListener(AsyncSupportListener asyncSupportListener) {
        asyncSupportListeners.add(asyncSupportListener);
        return this;
    }

    /**
     * Return the list of an {@link AsyncSupportListener}
     *
     * @return
     */
    public List<AsyncSupportListener> asyncSupportListeners() {
        return asyncSupportListeners;
    }

    /**
     * Add {@link BroadcasterListener} to all created {@link Broadcaster}
     */
    public AtmosphereFramework addBroadcasterListener(BroadcasterListener b) {
        broadcasterFactory.addBroadcasterListener(b);
        broadcasterListeners.add(b);
        return this;
    }

    /**
     * Add a {@link BroadcasterCacheInspector} which will be associated with the defined {@link BroadcasterCache}
     *
     * @param b {@link BroadcasterCacheInspector}
     * @return this;
     */
    public AtmosphereFramework addBroadcasterCacheInjector(BroadcasterCacheInspector b) {
        inspectors.add(b);
        return this;
    }

    /**
     * Return the list of {@link BroadcasterCacheInspector}
     *
     * @return the list of {@link BroadcasterCacheInspector}
     */
    protected ConcurrentLinkedQueue<BroadcasterCacheInspector> inspectors() {
        return inspectors;
    }

    /**
     * Return a configured instance of {@link AtmosphereConfig}
     *
     * @return a configured instance of {@link AtmosphereConfig}
     */
    public AtmosphereConfig getAtmosphereConfig() {
        return config;
    }

    @Override
    public ServletContext getServletContext() {
        return servletConfig.getServletContext();
    }

    public ServletConfig getServletConfig() {
        return servletConfig;
    }

    /**
     * Return the list of {@link BroadcastFilter}
     *
     * @return the list of {@link BroadcastFilter
     */
    public List<String> broadcasterFilters() {
        return broadcasterFilters;
    }

    /**
     * Add a {@link BroadcastFilter}
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
     * Returns true if {@link java.util.concurrent.ExecutorService} shared amongst all components.
     *
     * @return true if {@link java.util.concurrent.ExecutorService} shared amongst all components.
     */
    public boolean isShareExecutorServices() {
        return sharedThreadPools;
    }

    /**
     * Set to true to have a {@link java.util.concurrent.ExecutorService} shared amongst all components.
     *
     * @param sharedThreadPools
     * @return this
     */
    public AtmosphereFramework shareExecutorServices(boolean sharedThreadPools) {
        this.sharedThreadPools = sharedThreadPools;
        return this;
    }

    protected void autoConfigureService(ServletContext sc) throws IOException {
        final ClassLoader cl = Thread.currentThread().getContextClassLoader();

        String path = handlersPath != DEFAULT_HANDLER_PATH ? handlersPath : sc.getRealPath(handlersPath);
        AnnotationProcessor annotationProcessor = null;
        try {
            annotationProcessor = (AnnotationProcessor) getClass().getClassLoader().loadClass(annotationProcessorClassName).newInstance();
            logger.info("Atmosphere is using {} for processing annotation", annotationProcessorClassName);

            annotationProcessor.configure(this);

            if (packages.size() > 0) {
                for (String s : packages) {
                    annotationProcessor.scan(s);
                }
            }

            // Second try.
            if (!annotationFound) {
                if (path != null) {
                    annotationProcessor.scan(new File(path));
                }

                String pathLibs = libPath != DEFAULT_LIB_PATH ? libPath : sc.getRealPath(DEFAULT_LIB_PATH);
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
            logger.debug("Atmosphere's Service Annotation Not Supported. Please add https://github.com/rmuller/infomas-asl as dependencies or your own AnnotationProcessor to support @Service");
            logger.trace("", e);
            return;
        } finally {
            if (annotationProcessor != null) {
                annotationProcessor.destroy();
            }
        }
    }

    /**
     * The current {@link EndpointMapper} used to map request to {@link AtmosphereHandler}
     *
     * @return {@link EndpointMapper}
     */
    public EndpointMapper<AtmosphereHandlerWrapper> endPointMapper() {
        return endpointMapper;
    }

    /**
     * Set the {@link EndpointMapper}
     *
     * @param endpointMapper {@link EndpointMapper}
     * @return this
     */
    public AtmosphereFramework endPointMapper(EndpointMapper endpointMapper) {
        this.endpointMapper = endpointMapper;
        return this;
    }

    /**
     * Add support for package detecttion of Atmosphere's Component.
     *
     * @param clazz a Class
     * @return this.
     */
    public AtmosphereFramework addAnnotationPackage(Class<?> clazz) {
        packages.add(clazz.getPackage().getName());
        return this;
    }

    public void notify(Action.TYPE type, AtmosphereRequest request, AtmosphereResponse response) {
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
    }

    /**
     * Add an {@link WebSocketHandler} mapped to "/*"
     * return this
     */
    public AtmosphereFramework addWebSocketHandler(WebSocketHandler handler) {
        addWebSocketHandler("/*", handler);
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
        WebSocketProcessorFactory.getDefault().getWebSocketProcessor(this).registerWebSocketHandler(path, handler);
        addAtmosphereHandler(path, h, l);
        return this;
    }

    /**
     * Invoked when a {@link AnnotationProcessor} found annotation.
     *
     * @param b true when found
     * @return this
     */
    public AtmosphereFramework annotationScanned(boolean b) {
        annotationFound = b;
        return this;
    }

    /**
     * Return the list of packages the framework should look for {@link org.atmosphere.config.AtmosphereAnnotation}
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
}
