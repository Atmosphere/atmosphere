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

import org.atmosphere.cache.BroadcasterCacheInspector;
import org.atmosphere.config.ApplicationConfiguration;
import org.atmosphere.config.AtmosphereHandlerConfig;
import org.atmosphere.config.AtmosphereHandlerProperty;
import org.atmosphere.config.FrameworkConfiguration;
import org.atmosphere.container.BlockingIOCometSupport;
import org.atmosphere.handler.AbstractReflectorAtmosphereHandler;
import org.atmosphere.handler.ReflectorServletProcessor;
import org.atmosphere.inject.InjectableObjectFactory;
import org.atmosphere.util.AtmosphereConfigReader;
import org.atmosphere.util.DefaultUUIDProvider;
import org.atmosphere.util.EndpointMapper;
import org.atmosphere.util.ExecutorsFactory;
import org.atmosphere.util.IOUtils;
import org.atmosphere.util.IntrospectionUtils;
import org.atmosphere.util.ServletContextFactory;
import org.atmosphere.util.UUIDProvider;
import org.atmosphere.util.VoidServletConfig;
import org.atmosphere.websocket.DefaultWebSocketProcessor;
import org.atmosphere.websocket.WebSocket;
import org.atmosphere.websocket.WebSocketFactory;
import org.atmosphere.websocket.WebSocketHandler;
import org.atmosphere.websocket.WebSocketProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.Servlet;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URLDecoder;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.atmosphere.cpr.ApplicationConfig.ALLOW_QUERYSTRING_AS_REQUEST;
import static org.atmosphere.cpr.ApplicationConfig.BROADCASTER_CLASS;
import static org.atmosphere.cpr.ApplicationConfig.CONTENT_TYPE_FIRST_RESPONSE;
import static org.atmosphere.cpr.ApplicationConfig.DISABLE_ONSTATE_EVENT;
import static org.atmosphere.cpr.ApplicationConfig.META_SERVICE_PATH;
import static org.atmosphere.cpr.ApplicationConfig.PROPERTY_ALLOW_SESSION_TIMEOUT_REMOVAL;
import static org.atmosphere.cpr.ApplicationConfig.PROPERTY_ATMOSPHERE_XML;
import static org.atmosphere.cpr.ApplicationConfig.PROPERTY_BLOCKING_COMETSUPPORT;
import static org.atmosphere.cpr.ApplicationConfig.PROPERTY_COMET_SUPPORT;
import static org.atmosphere.cpr.ApplicationConfig.PROPERTY_NATIVE_COMETSUPPORT;
import static org.atmosphere.cpr.ApplicationConfig.PROPERTY_SESSION_SUPPORT;
import static org.atmosphere.cpr.ApplicationConfig.PROPERTY_THROW_EXCEPTION_ON_CLONED_REQUEST;
import static org.atmosphere.cpr.ApplicationConfig.PROPERTY_USE_STREAM;
import static org.atmosphere.cpr.ApplicationConfig.SUSPENDED_ATMOSPHERE_RESOURCE_UUID;
import static org.atmosphere.cpr.ApplicationConfig.USE_SERVLET_CONTEXT_PARAMETERS;
import static org.atmosphere.cpr.FrameworkConfig.ATMOSPHERE_CONFIG;
import static org.atmosphere.cpr.FrameworkConfig.CDI_INJECTOR;
import static org.atmosphere.cpr.FrameworkConfig.GUICE_INJECTOR;
import static org.atmosphere.cpr.FrameworkConfig.INJECT_LIBARY;
import static org.atmosphere.cpr.FrameworkConfig.JERSEY_BROADCASTER;
import static org.atmosphere.cpr.FrameworkConfig.SPRING_INJECTOR;
import static org.atmosphere.cpr.FrameworkConfig.THROW_EXCEPTION_ON_CLONED_REQUEST;
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
 * f.doCometSupport(AtmosphereRequest, AtmosphereResponse);
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
    public static final String ASYNC_IO = "io.async";

    protected static final Logger logger = LoggerFactory.getLogger(AtmosphereFramework.class);

    protected final FrameworkEventDispatcher eventDispatcher = new FrameworkEventDispatcher();
    protected final HashMap<String, String> initParams = new HashMap<>();
    protected final AtmosphereConfig config;
    protected final AtomicBoolean isCometSupportConfigured = new AtomicBoolean(false);
    protected final boolean isFilter;
    protected final ConcurrentLinkedQueue<String> objectFactoryType = new ConcurrentLinkedQueue<>();

    protected boolean useNativeImplementation;
    protected boolean useBlockingImplementation;
    protected boolean useStreamForFlushingComments = true;
    protected AsyncSupport<?> asyncSupport;
    protected boolean isCometSupportSpecified;
    protected boolean isSessionSupportSpecified;
    protected boolean isThrowExceptionOnClonedRequestSpecified;
    protected ServletConfig servletConfig;
    protected boolean autoDetectHandlers = true;
    protected String atmosphereDotXmlPath = DEFAULT_ATMOSPHERE_CONFIG_PATH;
    protected String metaServicePath = META_SERVICE;
    protected boolean isInit;
    protected boolean sharedThreadPools = true;
    protected boolean executeFirstSet;
    protected AtmosphereObjectFactory<?> objectFactory = new DefaultAtmosphereObjectFactory();
    protected final AtomicBoolean isDestroyed = new AtomicBoolean();
    protected boolean externalizeDestroy;
    private UUIDProvider uuidProvider = new DefaultUUIDProvider();
    protected Thread shutdownHook;
    protected final WebSocketConfig webSocketConfig;
    protected final InterceptorRegistry interceptorRegistry;
    protected final HandlerRegistry handlerRegistry;
    protected final BroadcasterSetup broadcasterSetup;
    protected final ClasspathScanner classpathScanner;
    public static final List<Class<? extends AtmosphereInterceptor>> DEFAULT_ATMOSPHERE_INTERCEPTORS =
            InterceptorRegistry.DEFAULT_ATMOSPHERE_INTERCEPTORS;
    private IllegalStateException initializationError;

    /**
     * An implementation of {@link AbstractReflectorAtmosphereHandler}.
     */
    public final static AtmosphereHandler REFLECTOR_ATMOSPHEREHANDLER = new AbstractReflectorAtmosphereHandler() {
        @Override
        public void onRequest(AtmosphereResource resource) {
            logger.trace("VoidHandler {}", resource.uuid());
        }

        @Override
        public void destroy() {
            logger.trace("VoidHandler");
        }
    };

    public void setAndConfigureAtmosphereResourceFactory(AtmosphereResourceFactory arFactory) {
        broadcasterSetup.setArFactory(arFactory);
        broadcasterSetup.arFactory().configure(config);
    }

    // Inner types promoted to top-level classes: AtmosphereHandlerWrapper, MetaServiceAction, DefaultAtmosphereObjectFactory

    /**
     * Create an AtmosphereFramework.
     */
    public AtmosphereFramework() {
        this(false, true);
    }

    /**
     * Create an AtmosphereFramework and initialize it via {@link AtmosphereFramework#init(jakarta.servlet.ServletConfig)}.
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
        config = newAtmosphereConfig();
        webSocketConfig = new WebSocketConfig(config);
        interceptorRegistry = new InterceptorRegistry(config);
        handlerRegistry = new HandlerRegistry(config, interceptorRegistry);
        broadcasterSetup = new BroadcasterSetup(config);
        broadcasterSetup.setHandlersSupplier(handlerRegistry::handlers);
        classpathScanner = new ClasspathScanner(config);
        interceptorRegistry.setHandlersSupplier(handlerRegistry::handlers);
        handlerRegistry.setBroadcasterFactorySupplier(() -> broadcasterSetup.broadcasterFactory());
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
        broadcasterSetup.populateBroadcasterType();
    }

    // --- Package-level component accessors for use by extracted classes ---

    HandlerRegistry getHandlerRegistry() { return handlerRegistry; }

    WebSocketConfig getWebSocketConfig() { return webSocketConfig; }

    boolean isAutoDetectHandlers() { return autoDetectHandlers; }

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
        handlerRegistry.addAtmosphereHandler(mapping, h, l);
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
        handlerRegistry.addAtmosphereHandler(mapping, h, broadcaster, l);
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
        handlerRegistry.addAtmosphereHandler(mapping, h, broadcasterId, l);
        return this;
    }

    protected AtmosphereHandlerWrapper createWrapperAndConfigureHandler(AtmosphereHandler h, String mapping, List<AtmosphereInterceptor> l) {
        return handlerRegistry.createWrapperAndConfigureHandler(h, mapping, l);
    }

    /**
     * Add an {@link AtmosphereHandler} serviced by the {@link Servlet}
     * This API is exposed to allow embedding an Atmosphere application.
     *
     * @param mapping The servlet mapping (servlet path)
     * @param h       implementation of an {@link AtmosphereHandler}
     */
    public AtmosphereFramework addAtmosphereHandler(String mapping, AtmosphereHandler h) {
        handlerRegistry.addAtmosphereHandler(mapping, h);
        return this;
    }

    public String normalizePath(String path) {
        return handlerRegistry.normalizePath(path);
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
        handlerRegistry.addAtmosphereHandler(mapping, h, broadcasterId);
        return this;
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
        handlerRegistry.addAtmosphereHandler(mapping, h, broadcaster);
        return this;
    }

    /**
     * Remove an {@link AtmosphereHandler}.
     *
     * @param mapping the mapping used when invoking {@link #addAtmosphereHandler(String, AtmosphereHandler)};
     * @return true if removed
     */
    public AtmosphereFramework removeAtmosphereHandler(String mapping) {
        handlerRegistry.removeAtmosphereHandler(mapping);
        return this;
    }

    /**
     * Remove all {@link AtmosphereHandler}s.
     */
    public AtmosphereFramework removeAllAtmosphereHandler() {
        handlerRegistry.removeAllAtmosphereHandler();
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
        classpathScanner.preventOOM();
    }

    /**
     * Initialize the AtmosphereFramework using the {@link ServletContext}.
     *
     * @param sc the {@link ServletContext}
     */
    public AtmosphereFramework init(final ServletConfig sc, boolean wrap) throws ServletException {
        if (isInit) return this;

        // Phase 0: Bootstrap
        servletConfig(sc, wrap);
        readSystemProperties();
        populateBroadcasterType();
        populateObjectFactoryType();
        loadMetaService();
        onPreInit();

        try {
            ServletContextFactory.getDefault().init(sc.getServletContext());

            // Phase 1: Parse configuration
            preventOOM();
            doInitParams(servletConfig);
            doInitParamsForWebSocket(servletConfig);
            lookupDefaultObjectFactoryType();

            if (logger.isTraceEnabled()) {
                asyncSupportListener(newClassInstance(AsyncSupportListener.class, AsyncSupportListenerAdapter.class));
            }
            configureObjectFactory();

            // Phase 2: Broadcaster infrastructure
            configureAnnotationPackages();
            configureBroadcasterFactory();
            configureMetaBroadcaster();
            configureAtmosphereResourceFactory();
            if (isSessionSupportSpecified) {
                sessionFactory();
            }

            // Phase 3: Classpath scanning
            configureScanningPackage(servletConfig, ApplicationConfig.ANNOTATION_PACKAGE);
            configureScanningPackage(servletConfig, FrameworkConfig.JERSEY2_SCANNING_PACKAGE);
            configureScanningPackage(servletConfig, FrameworkConfig.JERSEY_SCANNING_PACKAGE);
            defaultPackagesToScan();
            installAnnotationProcessor(servletConfig);
            autoConfigureService(servletConfig.getServletContext());

            // Phase 4: Re-configure after annotations
            configureBroadcasterFactory();
            patchContainer();
            configureBroadcaster();
            loadConfiguration(servletConfig);

            // Phase 5: Handler/interceptor initialization
            initWebSocket();
            initEndpointMapper();
            initDefaultSerializer();
            autoDetectContainer();
            configureWebDotXmlAtmosphereHandler(servletConfig);
            asyncSupport.init(servletConfig);
            initAtmosphereHandler(servletConfig);
            configureAtmosphereInterceptor(servletConfig);

            // Phase 6: Finalization
            analytics();
            if (sc.getServletContext() != null) {
                sc.getServletContext().setAttribute(BroadcasterFactory.class.getName(), broadcasterSetup.broadcasterFactory());
            }

            String s = config.getInitParameter(ApplicationConfig.BROADCASTER_SHARABLE_THREAD_POOLS);
            if (s != null) {
                sharedThreadPools = Boolean.parseBoolean(s);
            }

            this.shutdownHook = new Thread(AtmosphereFramework.this::destroy);
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

            if (t instanceof ServletException se) {
                throw se;
            }

            throw new ServletException(t);
        }
        isInit = true;
        config.initComplete();

        onPostInit();

        return this;
    }

    protected void servletConfig(final ServletConfig sc, boolean wrap) {
        if (wrap) {

            String value = sc.getServletContext().getInitParameter(USE_SERVLET_CONTEXT_PARAMETERS);
            final boolean useServletContextParameters = Boolean.parseBoolean(value);

            servletConfig = new ServletConfig() {

                final AtomicBoolean done = new AtomicBoolean();

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
                        Enumeration<String> en = sc.getInitParameterNames();
                        if (en != null) {
                            while (en.hasMoreElements()) {
                                String name = en.nextElement();
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
        FrameworkDiagnostics.info(this);
    }

    protected void universe() {
        Universe.broadcasterFactory(broadcasterSetup.broadcasterFactory());
        Universe.resourceFactory(broadcasterSetup.arFactory());
        Universe.sessionResourceFactory(broadcasterSetup.getSessionFactory());
        Universe.framework(this);
    }

    private void configureAnnotationPackages() {
        classpathScanner.configureAnnotationPackages();
    }

    protected void analytics() {
        FrameworkDiagnostics.analytics(config);
    }

    /**
     * Configure the list of {@link AtmosphereInterceptor}.
     *
     * @param sc a ServletConfig
     */
    protected void configureAtmosphereInterceptor(ServletConfig sc) {
        interceptorRegistry.configure(sc);
    }

    protected AtmosphereInterceptor newAInterceptor(Class<? extends AtmosphereInterceptor> a) {
        return interceptorRegistry.newInterceptor(a);
    }

    protected void configureWebDotXmlAtmosphereHandler(ServletConfig sc) {
        handlerRegistry.configureWebDotXmlAtmosphereHandler(sc);
    }

    protected void configureScanningPackage(ServletConfig sc, String value) {
        classpathScanner.configureScanningPackage(sc, value);
    }

    protected void defaultPackagesToScan() {
        classpathScanner.defaultPackagesToScan();
    }

    public void configureBroadcasterFactory() {
        broadcasterSetup.configureBroadcasterFactory();
    }

    protected void configureBroadcaster() {
        broadcasterSetup.configureBroadcaster();
    }

    protected void installAnnotationProcessor(ServletConfig sc) {
        classpathScanner.installAnnotationProcessor(sc);
    }

    protected void doInitParamsForWebSocket(ServletConfig sc) {
        webSocketConfig.doInitParams(sc);
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

        // Delegate broadcaster, scanner, interceptor, and handler params
        broadcasterSetup.parseInitParams(sc);
        classpathScanner.parseInitParams(sc);
        interceptorRegistry.parseInitParams(sc);

        s = sc.getInitParameter(PROPERTY_SESSION_SUPPORT);
        if (s == null) {
            s = sc.getServletContext().getInitParameter(PROPERTY_SESSION_SUPPORT);
        }

        if (s != null || SessionSupport.initializationHint) {
            boolean sessionSupport = Boolean.parseBoolean(s) || SessionSupport.initializationHint;
            config.setSupportSession(sessionSupport);
            if (sessionSupport && (sc.getServletContext().getMajorVersion() < 3 || !SessionSupport.initializationHint)) {
                logger.warn("SessionSupport error. Make sure you also define {} as a listener in web.xml, see https://github.com/Atmosphere/atmosphere/wiki/Enabling-HttpSession-Support", SessionSupport.class.getName());
            }
            isSessionSupportSpecified = true;
        }
        s = sc.getInitParameter(PROPERTY_ALLOW_SESSION_TIMEOUT_REMOVAL);
        if (s != null) {
            config.setSessionTimeoutRemovalAllowed(Boolean.parseBoolean(s));
        }
        s = sc.getInitParameter(PROPERTY_THROW_EXCEPTION_ON_CLONED_REQUEST);
        if (s != null) {
            config.setThrowExceptionOnCloned(Boolean.parseBoolean(s));
            isThrowExceptionOnClonedRequestSpecified = true;
        }
        s = sc.getInitParameter(DISABLE_ONSTATE_EVENT);
        if (s != null) {
            initParams.put(DISABLE_ONSTATE_EVENT, s);
        } else {
            initParams.put(DISABLE_ONSTATE_EVENT, "false");
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
            handlerRegistry.mappingRegex(s);
        }
    }

    public void loadConfiguration(ServletConfig sc) throws ServletException {
        try {
            classpathScanner.loadConfiguration(sc);
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
        return classpathScanner.detectSupportedFramework(sc);
    }

    protected void configureDetectedFramework(ReflectorServletProcessor rsp, boolean isJersey) {
        classpathScanner.configureDetectedFramework(rsp, isJersey);
    }

    protected String lookupDefaultBroadcasterType(String defaultB) {
        return broadcasterSetup.lookupDefaultBroadcasterType(defaultB);
    }

    boolean autodetectBroadcaster() {
        return broadcasterSetup.autodetectBroadcaster();
    }

    protected AtmosphereObjectFactory<?> lookupDefaultObjectFactoryType() {

        if (objectFactory != null && !DefaultAtmosphereObjectFactory.class.getName().equals(objectFactory.getClass()
                .getName())) return objectFactory;

        for (String b : objectFactoryType) {
            try {
                var c = Class.forName(b);
                objectFactory = (AtmosphereObjectFactory<?>) c.getDeclaredConstructor().newInstance();
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
                logger.trace("jakarta.inject.Inject nor installed. Using DefaultAtmosphereObjectFactory");
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
     * @throws jakarta.servlet.ServletException
     * @Deprecated
     */
    public void initAtmosphereHandler(ServletConfig sc) throws ServletException {
        initAtmosphereHandler();
    }

    public void initAtmosphereHandler() throws ServletException {
        handlerRegistry.initAtmosphereHandler();
    }

    public void checkWebSocketSupportState() {
        handlerRegistry.checkWebSocketSupportState();
    }

    public void initWebSocket() {
        webSocketConfig.initWebSocket();
    }

    public void initEndpointMapper() {
        handlerRegistry.initEndpointMapper();
    }

    protected void closeAtmosphereResource() {
        for (AtmosphereResource r : config.resourcesFactory().findAll()) {
            try {
                r.resume().close();
            } catch (Exception e) {
                logger.trace("", e);
            }
        }
    }

    public AtmosphereFramework destroy() {
        if (isDestroyed.getAndSet(true)) return this;

        // Phase 1: Pre-destroy callbacks and close active resources
        onPreDestroy();
        closeAtmosphereResource();

        // Phase 2: Destroy interceptors and config
        destroyInterceptors();
        config.destroy();

        // Phase 3: Destroy broadcaster infrastructure
        broadcasterSetup.destroyFactories();

        // Phase 4: Destroy async support and handlers
        if (asyncSupport != null && asyncSupport instanceof AsynchronousProcessor ap) {
            ap.shutdown();
        }
        for (Entry<String, AtmosphereHandlerWrapper> entry : handlerRegistry.handlers().entrySet()) {
            AtmosphereHandlerWrapper handlerWrapper = entry.getValue();
            try {
                handlerWrapper.atmosphereHandler().destroy();
            } catch (Throwable t) {
                logger.warn("", t);
            }
        }

        // Phase 5: Reset thread pools and state
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

        config.properties().clear();
        return this;
    }

    protected void destroyInterceptors() {
        interceptorRegistry.destroyInterceptors(handlerRegistry.handlers());
    }

    public AtmosphereFramework resetStates() {
        isInit = false;
        executeFirstSet = false;

        broadcasterSetup.clear();
        eventDispatcher.clear();
        classpathScanner.clear();
        initParams.clear();
        handlerRegistry.clear();
        objectFactoryType.clear();
        interceptorRegistry.clear();

        return this;
    }

    @SuppressWarnings("unchecked")
    protected void loadMetaService() {
        try {
            Map<String, MetaServiceAction> config = (Map<String, MetaServiceAction>) servletConfig.getServletContext().getAttribute(MetaServiceAction.class.getName());
            if (config == null) {
                config = IOUtils.readServiceFile(metaServicePath + AtmosphereFramework.class.getName());
            }

            for (final Map.Entry<String, MetaServiceAction> action : config.entrySet()) {
                try {
                    final Class<?> c = IOUtils.loadClass(AtmosphereFramework.class, action.getKey());
                    action.getValue().apply(this, c);
                } catch (ClassNotFoundException ex) {
                    if (action.getKey().startsWith(ASYNC_IO)) {
                        logger.trace("Unable to load class {}", ex.getMessage());
                    } else {
                        logger.warn("", ex);
                    }
                }
            }
        } catch (Exception ex) {
            logger.warn("", ex);
        }
    }

    /**
     * Load AtmosphereHandler defined under META-INF/atmosphere.xml.
     *
     * @param stream The input stream we read from.
     * @param c      The classloader
     */
    @SuppressWarnings("unchecked")
    protected void loadAtmosphereDotXml(InputStream stream, ClassLoader c)
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

                    if (handlerProperty.getValue() != null && handlerProperty.getValue().contains("jersey")) {
                        initParams.put(DISABLE_ONSTATE_EVENT, "true");
                        useStreamForFlushingComments = true;
                        broadcasterSetup.setBroadcasterClassName(lookupDefaultBroadcasterType(JERSEY_BROADCASTER));
                        broadcasterSetup.broadcasterFactory().destroy();
                        broadcasterSetup.setBroadcasterFactory(null);
                        configureBroadcasterFactory();
                        configureBroadcaster();
                    }

                    if (handler != null) {
                        IntrospectionUtils.setProperty(handler, handlerProperty.getName(), handlerProperty.getValue());
                        IntrospectionUtils.addProperty(handler, handlerProperty.getName(), handlerProperty.getValue());
                    }
                }

                sessionSupport(Boolean.parseBoolean(atmoHandler.getSupportSession()));

                if (handler != null) {
                    String broadcasterClass = atmoHandler.getBroadcaster();
                    Broadcaster b;
                    /*
                     * If there is more than one AtmosphereHandler defined, their Broadcaster
                     * may clash each other with the BroadcasterFactory. In that case we will use the
                     * last one defined.
                     */
                    if (broadcasterClass != null) {
                        broadcasterSetup.setBroadcasterClassName(broadcasterClass);
                        ClassLoader cl = Thread.currentThread().getContextClassLoader();
                        Class<? extends Broadcaster> bc = (Class<? extends Broadcaster>) cl.loadClass(broadcasterSetup.broadcasterClassName());
                        broadcasterSetup.setBroadcasterFactory(newClassInstance(BroadcasterFactory.class, DefaultBroadcasterFactory.class));
                        broadcasterSetup.broadcasterFactory().configure(bc, broadcasterSetup.broadcasterLifeCyclePolicy(), config);
                    }

                    b = broadcasterSetup.broadcasterFactory().lookup(atmoHandler.getContextRoot(), true);

                    var wrapper = new AtmosphereHandlerWrapper(handler, b, config);
                    handlerRegistry.handlers().put(handlerRegistry.normalizePath(atmoHandler.getContextRoot()), wrapper);

                    String bc = atmoHandler.getBroadcasterCache();
                    if (bc != null) {
                        broadcasterSetup.setBroadcasterCacheClassName(bc);
                    }

                    if (atmoHandler.getAsyncSupport() != null) {
                        asyncSupport = (AsyncSupport<?>) c.loadClass(atmoHandler.getAsyncSupport())
                                .getDeclaredConstructor(new Class[]{AtmosphereConfig.class})
                                .newInstance(new Object[]{config});
                    }

                    if (atmoHandler.getBroadcastFilterClasses() != null) {
                        broadcasterSetup.broadcasterFilters().addAll(atmoHandler.getBroadcastFilterClasses());
                    }

                    var l = new LinkedList<AtmosphereInterceptor>();
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
    public AtmosphereFramework setAsyncSupport(AsyncSupport<?> asyncSupport) {
        this.asyncSupport = asyncSupport;
        return this;
    }

    /**
     * Return the current {@link AsyncSupport}.
     *
     * @return the current {@link AsyncSupport}
     */
    public AsyncSupport<?> getAsyncSupport() {
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
                    .resolve(useNativeImplementation, useBlockingImplementation, webSocketConfig.isUseServlet30()));
        }
    }

    /**
     * Auto detect instance of {@link AtmosphereHandler} in case META-INF/atmosphere.xml
     * is missing.
     *
     * @param servletContext {@link ServletContext}
     * @param classloader    {@link ClassLoader} to load the class.
     * @throws java.net.MalformedURLException
     */
    public void autoDetectAtmosphereHandlers(ServletContext servletContext, ClassLoader classloader)
            throws MalformedURLException {
        classpathScanner.autoDetectAtmosphereHandlers(servletContext, classloader);
    }

    public void loadAtmosphereHandlersFromPath(ClassLoader classloader, String realPath) {
        classpathScanner.loadAtmosphereHandlersFromPath(classloader, realPath);
    }

    /**
     * Auto detect instance of {@link org.atmosphere.websocket.WebSocketHandler} in case META-INF/atmosphere.xml
     * is missing.
     *
     * @param servletContext {@link ServletContext}
     * @param classloader    {@link ClassLoader} to load the class.
     * @throws java.net.MalformedURLException
     */
    protected void autoDetectWebSocketHandler(ServletContext servletContext, ClassLoader classloader)
            throws MalformedURLException {
        classpathScanner.autoDetectWebSocketHandler(servletContext, classloader);
    }

    protected void loadWebSocketFromPath(ClassLoader classloader, String realPath) {
        classpathScanner.loadWebSocketFromPath(classloader, realPath);
    }

    /**
     * Get a list of possible candidates to load as {@link AtmosphereHandler}.
     *
     * @param req {@link AtmosphereRequest}
     */
    public AtmosphereFramework configureRequestResponse(AtmosphereRequest req, AtmosphereResponse res) throws UnsupportedEncodingException {
        req.setAttribute(PROPERTY_USE_STREAM, useStreamForFlushingComments);
        req.setAttribute(BROADCASTER_CLASS, broadcasterSetup.broadcasterClassName());
        req.setAttribute(ATMOSPHERE_CONFIG, config);
        req.setAttribute(THROW_EXCEPTION_ON_CLONED_REQUEST, "" + config.isThrowExceptionOnCloned());

        boolean skip = true;
        String s = config.getInitParameter(ALLOW_QUERYSTRING_AS_REQUEST);
        if (s != null) {
            skip = Boolean.parseBoolean(s);
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
            if (Boolean.parseBoolean(unique)) {
                s = (String) req.getAttribute(SUSPENDED_ATMOSPHERE_RESOURCE_UUID);
            }
        }

        if (s == null || s.equals("0")) {
            s = config.uuidProvider().generateUuid();
            res.setHeader(HeaderConfig.X_FIRST_REQUEST, "true");
            res.setHeader(X_ATMOSPHERE_TRACKING_ID, s);
            String contentType = config.getInitParameter(CONTENT_TYPE_FIRST_RESPONSE);
            if (contentType != null) {
                res.setHeader("Content-Type", contentType);
            }
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
            logger.error("AtmosphereFramework exception", ex);
            throw ex;
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
        return broadcasterSetup.broadcasterClassName();
    }

    /**
     * Set the default {@link Broadcaster} class name.
     *
     * @param bccn the broadcasterClassName to set
     */
    public AtmosphereFramework setDefaultBroadcasterClassName(String bccn) {
        if (broadcasterSetup.isBroadcasterSpecified()) {
            logger.trace("Broadcaster {} already set in web.xml", broadcasterSetup.broadcasterClassName());
            return this;
        }
        broadcasterSetup.setBroadcasterSpecified(true);

        broadcasterSetup.setBroadcasterClassName(bccn);

        // Must reconfigure.
        broadcasterSetup.setBroadcasterFactory(null);
        configureBroadcasterFactory();

        // We must recreate all previously created Broadcaster.
        for (AtmosphereHandlerWrapper w : handlerRegistry.handlers().values()) {
            // If case one listener is initializing the framework.
            if (w.broadcaster() != null) {
                w.setBroadcaster(broadcasterSetup.broadcasterFactory().lookup(w.broadcaster().getID(), true));
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
        return webSocketConfig.isUseServlet30();
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
        if (broadcasterSetup.broadcasterFactory() == null) {
            configureBroadcasterFactory();
        }
        return broadcasterSetup.broadcasterFactory();
    }

    /**
     * Set the {@link BroadcasterFactory} which is used by Atmosphere to construct
     * {@link Broadcaster}.
     *
     * @return {@link BroadcasterFactory}
     */
    public AtmosphereFramework setBroadcasterFactory(final BroadcasterFactory broadcasterFactory) {
        broadcasterSetup.setBroadcasterFactory(broadcasterFactory);
        configureBroadcaster();
        return this;
    }

    /**
     * Return the {@link org.atmosphere.cpr.BroadcasterCache} class name.
     *
     * @return the {@link org.atmosphere.cpr.BroadcasterCache} class name
     */
    public String getBroadcasterCacheClassName() {
        return broadcasterSetup.broadcasterCacheClassName();
    }

    /**
     * Set the {@link org.atmosphere.cpr.BroadcasterCache} class name.
     *
     * @param broadcasterCacheClassName
     */
    public AtmosphereFramework setBroadcasterCacheClassName(String broadcasterCacheClassName) {
        broadcasterSetup.setBroadcasterCacheClassName(broadcasterCacheClassName);
        return this;
    }

    /**
     * Add a new Broadcaster class name that AtmosphereServlet can use when initializing requests, and when the
     * atmosphere.xml broadcaster element is unspecified.
     *
     * @param broadcasterTypeString
     */
    public AtmosphereFramework addBroadcasterType(String broadcasterTypeString) {
        broadcasterSetup.broadcasterTypes().add(broadcasterTypeString);
        return this;
    }

    public ConcurrentLinkedQueue<String> broadcasterTypes() {
        return broadcasterSetup.broadcasterTypes();
    }

    public String getWebSocketProtocolClassName() {
        return webSocketConfig.getProtocolClassName();
    }

    public AtmosphereFramework setWebSocketProtocolClassName(String webSocketProtocolClassName) {
        webSocketConfig.setProtocolClassName(webSocketProtocolClassName);
        return this;
    }

    public Map<String, AtmosphereHandlerWrapper> getAtmosphereHandlers() {
        return handlerRegistry.handlers();
    }

    protected Map<String, String> configureQueryStringAsRequest(AtmosphereRequest request) {
        var headers = new HashMap<String, String>();

        var q = new StringBuilder();
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
                disallowModifyQueryString.isEmpty() || "false".equalsIgnoreCase(disallowModifyQueryString)) {
            if (q.length() > 0) {
                q.deleteCharAt(q.length() - 1);
            }
            request.queryString(q.toString());
        }

        logger.trace("Query String translated to headers {}", headers);
        return headers;
    }

    public WebSocketProtocol getWebSocketProtocol() {
        return webSocketConfig.getProtocol();
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
        return classpathScanner.handlersPath();
    }

    public AtmosphereFramework setHandlersPath(String handlersPath) {
        classpathScanner.setHandlersPath(handlersPath);
        return this;
    }

    /**
     * Return the location of the JARs containing the application classes. Default is WEB-INF/lib.
     *
     * @return the location of the JARs containing the application classes. Default is WEB-INF/lib
     */
    public String getLibPath() {
        return classpathScanner.libPath();
    }

    /**
     * Set the location of the JARs containing the application.
     *
     * @param libPath the location of the JARs containing the application.
     * @return this
     */
    public AtmosphereFramework setLibPath(String libPath) {
        classpathScanner.setLibPath(libPath);
        return this;
    }

    /**
     * The current {@link org.atmosphere.websocket.WebSocketProcessor} used to handle websocket requests.
     *
     * @return {@link org.atmosphere.websocket.WebSocketProcessor}
     */
    public String getWebSocketProcessorClassName() {
        return webSocketConfig.getProcessorClassName();
    }

    /**
     * Set the {@link org.atmosphere.websocket.WebSocketProcessor} class name used to process WebSocket requests. Default is
     * {@link DefaultWebSocketProcessor}
     *
     * @param webSocketProcessorClassName {@link org.atmosphere.websocket.WebSocketProcessor}
     * @return this
     */
    public AtmosphereFramework setWebsocketProcessorClassName(String webSocketProcessorClassName) {
        webSocketConfig.setProcessorClassName(webSocketProcessorClassName);
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
        interceptorRegistry.addInterceptor(c, isInit);
        return this;
    }

    protected void addDefaultOrAppInterceptors() {
        interceptorRegistry.addDefaultOrAppInterceptors();
    }

    protected void addInterceptorToAllWrappers(AtmosphereInterceptor c) {
        interceptorRegistry.addInterceptorToAllWrappers(c);
    }

    protected void addInterceptorToWrapper(AtmosphereHandlerWrapper wrapper, AtmosphereInterceptor c) {
        interceptorRegistry.addInterceptorToWrapper(wrapper, c);
    }

    protected void addInterceptorToWrapper(AtmosphereHandlerWrapper wrapper, List<AtmosphereInterceptor> interceptors) {
        interceptorRegistry.addInterceptorToWrapper(wrapper, interceptors);
    }

    public LinkedList<AtmosphereInterceptor> interceptors() {
        return interceptorRegistry.interceptors();
    }

    /**
     * Find an {@link AtmosphereInterceptor} of the given type.
     *
     * @param c the interceptor class to look for
     * @return the interceptor instance, or null if not found.
     * @deprecated Use {@link #findInterceptor(Class)} which returns {@link Optional} instead of null.
     */
    @Deprecated(since = "4.0.0", forRemoval = false)
    public <T extends AtmosphereInterceptor> T interceptor(Class<T> c) {
        return interceptorRegistry.interceptor(c);
    }

    /**
     * Find an {@link AtmosphereInterceptor} of the given type.
     * <p>
     * This is the preferred alternative to {@link #interceptor(Class)} as it returns an {@link Optional}
     * instead of null, making the absent-interceptor case explicit at the call site.
     *
     * @param c the interceptor class to look for
     * @return an {@link Optional} containing the interceptor, or empty if not found.
     */
    public <T extends AtmosphereInterceptor> Optional<T> findInterceptor(Class<T> c) {
        return interceptorRegistry.findInterceptor(c);
    }
    public AtmosphereFramework annotationProcessorClassName(String annotationProcessorClassName) {
        classpathScanner.setAnnotationProcessorClassName(annotationProcessorClassName);
        return this;
    }

    /**
     * Return the {@link FrameworkEventDispatcher} for managing listeners and events.
     *
     * @return the event dispatcher
     */
    public FrameworkEventDispatcher events() {
        return eventDispatcher;
    }

    /**
     * Return the {@link WebSocketConfig} for managing WebSocket settings.
     *
     * @return the WebSocket configuration
     */
    public WebSocketConfig webSocket() {
        return webSocketConfig;
    }

    /**
     * Return the {@link InterceptorRegistry} for managing interceptor lifecycle.
     *
     * @return the interceptor registry
     */
    public InterceptorRegistry interceptorRegistry() {
        return interceptorRegistry;
    }

    /**
     * Return the {@link HandlerRegistry} for managing handler registration.
     *
     * @return the handler registry
     */
    public HandlerRegistry handlerRegistry() {
        return handlerRegistry;
    }

    /**
     * Add an {@link AsyncSupportListener}.
     *
     * @param asyncSupportListener an {@link AsyncSupportListener}
     * @return this;
     */
    public AtmosphereFramework asyncSupportListener(AsyncSupportListener asyncSupportListener) {
        eventDispatcher.addAsyncSupportListener(asyncSupportListener);
        return this;
    }

    /**
     * Return the list of {@link AsyncSupportListener}s.
     *
     * @return
     */
    public List<AsyncSupportListener> asyncSupportListeners() {
        return eventDispatcher.asyncSupportListeners();
    }

    /**
     * Add {@link BroadcasterListener} to all created {@link Broadcaster}s.
     */
    public AtmosphereFramework addBroadcasterListener(BroadcasterListener b) {
        broadcasterSetup.broadcasterFactory().addBroadcasterListener(b);
        broadcasterSetup.broadcasterListeners().add(b);
        return this;
    }

    /**
     * Add {@link BroadcasterCacheListener} to the {@link BroadcasterCache}.
     */
    public AtmosphereFramework addBroadcasterCacheListener(BroadcasterCacheListener b) {
        broadcasterSetup.broadcasterCacheListeners().add(b);
        return this;
    }

    public List<BroadcasterCacheListener> broadcasterCacheListeners() {
        return broadcasterSetup.broadcasterCacheListeners();
    }

    /**
     * Add a {@link BroadcasterCacheInspector} which will be associated with the defined {@link BroadcasterCache}.
     *
     * @param b {@link BroadcasterCacheInspector}
     * @return this;
     */
    public AtmosphereFramework addBroadcasterCacheInjector(BroadcasterCacheInspector b) {
        broadcasterSetup.inspectors().add(b);
        return this;
    }

    /**
     * Return the list of {@link BroadcasterCacheInspector}s.
     *
     * @return the list of {@link BroadcasterCacheInspector}s
     */
    public ConcurrentLinkedQueue<BroadcasterCacheInspector> inspectors() {
        return broadcasterSetup.inspectors();
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
        return broadcasterSetup.broadcasterFilters();
    }

    /**
     * Add a {@link BroadcastFilter}.
     *
     * @return
     */
    public AtmosphereFramework broadcasterFilters(BroadcastFilter f) {
        broadcasterSetup.broadcasterFilters().add(f.getClass().getName());

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
        classpathScanner.autoConfigureService(sc);
    }

    /**
     * The current {@link EndpointMapper} used to map requests to {@link AtmosphereHandler}.
     *
     * @return {@link EndpointMapper}
     */
    public EndpointMapper<AtmosphereHandlerWrapper> endPointMapper() {
        return handlerRegistry.endPointMapper();
    }

    /**
     * Set the {@link EndpointMapper}.
     *
     * @param endpointMapper {@link EndpointMapper}
     * @return this
     */
    public AtmosphereFramework endPointMapper(EndpointMapper<?> endpointMapper) {
        handlerRegistry.endPointMapper(endpointMapper);
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
            classpathScanner.packages().add(clazz.getPackage().getName());
        }
        return this;
    }

    public AtmosphereFramework notify(Action.TYPE type, AtmosphereRequest request, AtmosphereResponse response) {
        eventDispatcher.notify(type, request, response);
        return this;
    }

    public AtmosphereFramework notifyDestroyed(String uuid) {
        eventDispatcher.notifyDestroyed(uuid);
        return this;
    }

    public AtmosphereFramework notifySuspended(String uuid) {
        eventDispatcher.notifySuspended(uuid);
        return this;
    }

    /**
     * Add an {@link WebSocketHandler} mapped to "/*".
     * return this
     */
    public AtmosphereFramework addWebSocketHandler(WebSocketHandler handler) {
        handlerRegistry.addWebSocketHandler(handler);
        return this;
    }

    /**
     * Add an {@link WebSocketHandler} mapped to the path.
     * return this
     */
    public AtmosphereFramework addWebSocketHandler(String path, WebSocketHandler handler) {
        handlerRegistry.addWebSocketHandler(path, handler);
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
        handlerRegistry.addWebSocketHandler(path, handler, h);
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
        handlerRegistry.addWebSocketHandler(path, handler, h, l);
        return this;
    }

    /**
     * Invoked when a {@link AnnotationProcessor} found an annotation.
     *
     * @param b true when found
     * @return this
     */
    public AtmosphereFramework annotationScanned(boolean b) {
        classpathScanner.setAnnotationFound(b);
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
        return classpathScanner.packages();
    }

    /**
     * Return the list of packages the framework should look for {@link org.atmosphere.config.AtmosphereAnnotation}.
     *
     * @return the list of packages the framework should look for {@link org.atmosphere.config.AtmosphereAnnotation}
     */
    public List<String> customAnnotationPackages() {
        return classpathScanner.annotationPackages();
    }

    /**
     * Add a package containing classes annotated with {@link org.atmosphere.config.AtmosphereAnnotation}.
     *
     * @param p a package
     * @return this;
     */
    public AtmosphereFramework addCustomAnnotationPackage(Class<?> p) {
        classpathScanner.annotationPackages().addLast(p.getPackage().getName());
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
    public void objectFactory(AtmosphereObjectFactory<?> objectFactory) {
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
        return classpathScanner.annotationProcessor();
    }

    /**
     * Was a {@link Broadcaster} defined in web.xml or programmatically added.
     *
     * @return true is defined.
     */
    public boolean isBroadcasterSpecified() {
        return broadcasterSetup.isBroadcasterSpecified();
    }

    protected void configureObjectFactory() {
        String s = config.getInitParameter(ApplicationConfig.OBJECT_FACTORY);
        if (s != null) {
            try {
                AtmosphereObjectFactory<?> aci = (AtmosphereObjectFactory<?>) IOUtils.loadClass(getClass(), s).getDeclaredConstructor().newInstance();
                logger.debug("Found ObjectFactory {}", aci.getClass().getName());
                objectFactory(aci);
            } catch (Exception ex) {
                logger.warn("Unable to load AtmosphereClassInstantiator instance", ex);
            }
        }

        if (!(objectFactory instanceof DefaultAtmosphereObjectFactory)) {
            logger.trace("ObjectFactory already set to {}", objectFactory);
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
        interceptorRegistry.excludeInterceptor(interceptor);
        return this;
    }

    public AtmosphereFramework filterManipulator(BroadcasterConfig.FilterManipulator m) {
        broadcasterSetup.filterManipulators().add(m);
        return this;
    }

    public List<BroadcasterConfig.FilterManipulator> filterManipulators() {
        return broadcasterSetup.filterManipulators();
    }

    public boolean isAServletFilter() {
        return isFilter;
    }

    public ConcurrentLinkedQueue<String> objectFactoryType() {
        return objectFactoryType;
    }

    public String mappingRegex() {
        return handlerRegistry.mappingRegex();
    }

    public AtmosphereFramework mappingRegex(String mappingRegex) {
        handlerRegistry.mappingRegex(mappingRegex);
        return this;
    }

    public void setUseServlet30(boolean useServlet30) {
        webSocketConfig.setUseServlet30(useServlet30);
    }

    public boolean webSocketEnabled() {
        return webSocketConfig.isEnabled();
    }

    public AtmosphereFramework webSocketEnabled(boolean webSocketEnabled) {
        webSocketConfig.setEnabled(webSocketEnabled);
        return this;
    }

    public String broadcasterLifeCyclePolicy() {
        return broadcasterSetup.broadcasterLifeCyclePolicy();
    }

    public AtmosphereFramework broadcasterLifeCyclePolicy(String broadcasterLifeCyclePolicy) {
        broadcasterSetup.setBroadcasterLifeCyclePolicy(broadcasterLifeCyclePolicy);
        return this;
    }

    public List<BroadcasterListener> broadcasterListeners() {
        return broadcasterSetup.broadcasterListeners();
    }

    public boolean sharedThreadPools() {
        return sharedThreadPools;
    }

    public AtmosphereFramework sharedThreadPools(boolean sharedThreadPools) {
        this.sharedThreadPools = sharedThreadPools;
        return this;
    }

    public boolean allowAllClassesScan() {
        return classpathScanner.allowAllClassesScan();
    }

    public AtmosphereFramework allowAllClassesScan(boolean allowAllClassesScan) {
        classpathScanner.setAllowAllClassesScan(allowAllClassesScan);
        return this;
    }

    public AtmosphereObjectFactory<?> objectFactory() {
        return objectFactory;
    }

    public boolean externalizeDestroy() {
        return externalizeDestroy;
    }

    public List<String> excludedInterceptors() {
        return interceptorRegistry.excludedInterceptors();
    }

    public Class<? extends AtmosphereInterceptor>[] defaultInterceptors() {
        return interceptorRegistry.defaultInterceptors();
    }

    public AtmosphereResourceFactory atmosphereFactory() {
        return broadcasterSetup.getOrCreateAtmosphereFactory();
    }

    private AtmosphereFramework configureAtmosphereResourceFactory() {
        broadcasterSetup.configureAtmosphereResourceFactory();
        return this;
    }

    public MetaBroadcaster metaBroadcaster() {
        return broadcasterSetup.metaBroadcaster();
    }

    private AtmosphereFramework configureMetaBroadcaster() {
        broadcasterSetup.configureMetaBroadcaster();
        return this;
    }

    /**
     * Get the default {@link org.atmosphere.cpr.Serializer} class name to use for {@link org.atmosphere.cpr.AtmosphereResource}s.
     *
     * @return the class name as a string, might be null if not configured
     */
    public String getDefaultSerializerClassName() {
        return broadcasterSetup.defaultSerializerClassName();
    }

    /**
     * Get the default {@link org.atmosphere.cpr.Serializer} class to use for {@link org.atmosphere.cpr.AtmosphereResource}s.
     *
     * @return the class, might be null if not configured
     */
    public Class<Serializer> getDefaultSerializerClass() {
        return broadcasterSetup.defaultSerializerClass();
    }

    /**
     * Set the default {@link org.atmosphere.cpr.Serializer} class name to use for {@link org.atmosphere.cpr.AtmosphereResource}s.
     *
     * @param defaultSerializerClassName the class name to use
     * @return this
     */
    public AtmosphereFramework setDefaultSerializerClassName(String defaultSerializerClassName) {
        broadcasterSetup.setDefaultSerializerClassName(defaultSerializerClassName);
        initDefaultSerializer();
        return this;
    }

    private void initDefaultSerializer() {
        broadcasterSetup.initDefaultSerializer();
    }

    /**
     * Return the {@link AtmosphereResourceSessionFactory}
     *
     * @return the AtmosphereResourceSessionFactory
     */
    public AtmosphereResourceSessionFactory sessionFactory() {
        return broadcasterSetup.getOrCreateSessionFactory();
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
        eventDispatcher.addFrameworkListener(l);
        return this;
    }

    /**
     * Return the list of {@link org.atmosphere.cpr.AtmosphereFrameworkListener}
     *
     * @return {@link org.atmosphere.cpr.AtmosphereFrameworkListener}
     */
    public List<AtmosphereFrameworkListener> frameworkListeners() {
        return eventDispatcher.frameworkListeners();
    }

    protected void onPreInit() {
        eventDispatcher.onPreInit(this);
    }

    protected void onPostInit() {
        eventDispatcher.onPostInit(this);
    }

    protected void onPreDestroy() {
        eventDispatcher.onPreDestroy(this);
    }

    protected void onPostDestroy() {
        eventDispatcher.onPostDestroy(this);
    }

    /**
     * Return the list of {@link org.atmosphere.cpr.AtmosphereResourceListener}
     *
     * @return the list of {@link org.atmosphere.cpr.AtmosphereResourceListener}
     */
    public List<AtmosphereResourceListener> atmosphereResourceListeners() {
        return eventDispatcher.atmosphereResourceListeners();
    }

    /**
     * Add a {@link org.atmosphere.cpr.AtmosphereResourceListener}
     *
     * @param atmosphereResourceListener a {@link org.atmosphere.cpr.AtmosphereResourceListener}
     * @return this
     */
    public AtmosphereFramework atmosphereResourceListener(AtmosphereResourceListener atmosphereResourceListener) {
        eventDispatcher.addAtmosphereResourceListener(atmosphereResourceListener);
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
        return webSocketConfig.getFactory();
    }

    /**
     * Configure the {@link WebSocketFactory}
     *
     * @param webSocketFactory the {@link WebSocketFactory}
     * @return this
     */
    public AtmosphereFramework webSocketFactory(WebSocketFactory webSocketFactory) {
        webSocketConfig.setFactory(webSocketFactory);
        return this;
    }

    /**
     * If a {@link ContainerInitializer} fails, set the field initializationError for later logging purposes.
     *
     * @param initializationError
     */
    public void initializationError(IllegalStateException initializationError) {
        this.initializationError = initializationError;
    }
}
