/*
 * Copyright 2012 Jeanfrancois Arcand
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

import org.atmosphere.config.ApplicationConfiguration;
import org.atmosphere.config.AtmosphereHandlerConfig;
import org.atmosphere.config.AtmosphereHandlerProperty;
import org.atmosphere.config.FrameworkConfiguration;
import org.atmosphere.container.BlockingIOCometSupport;
import org.atmosphere.container.Tomcat7Servlet30SupportWithWebSocket;
import org.atmosphere.di.InjectorProvider;
import org.atmosphere.di.ServletContextHolder;
import org.atmosphere.di.ServletContextProvider;
import org.atmosphere.handler.AbstractReflectorAtmosphereHandler;
import org.atmosphere.handler.ReflectorServletProcessor;
import org.atmosphere.interceptor.JSONPAtmosphereInterceptor;
import org.atmosphere.interceptor.SSEAtmosphereInterceptor;
import org.atmosphere.util.AtmosphereConfigReader;
import org.atmosphere.util.IntrospectionUtils;
import org.atmosphere.util.Version;
import org.atmosphere.websocket.WebSocket;
import org.atmosphere.websocket.WebSocketProtocol;
import org.atmosphere.websocket.protocol.SimpleHttpProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.Servlet;
import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
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
import static org.atmosphere.cpr.ApplicationConfig.PROPERTY_USE_STREAM;
import static org.atmosphere.cpr.ApplicationConfig.RESUME_AND_KEEPALIVE;
import static org.atmosphere.cpr.ApplicationConfig.WEBSOCKET_PROTOCOL;
import static org.atmosphere.cpr.ApplicationConfig.WEBSOCKET_SUPPORT;
import static org.atmosphere.cpr.FrameworkConfig.ATMOSPHERE_CONFIG;
import static org.atmosphere.cpr.FrameworkConfig.HAZELCAST_BROADCASTER;
import static org.atmosphere.cpr.FrameworkConfig.JERSEY_BROADCASTER;
import static org.atmosphere.cpr.FrameworkConfig.JERSEY_CONTAINER;
import static org.atmosphere.cpr.FrameworkConfig.JGROUPS_BROADCASTER;
import static org.atmosphere.cpr.FrameworkConfig.JMS_BROADCASTER;
import static org.atmosphere.cpr.FrameworkConfig.REDIS_BROADCASTER;
import static org.atmosphere.cpr.FrameworkConfig.WRITE_HEADERS;
import static org.atmosphere.cpr.FrameworkConfig.XMPP_BROADCASTER;
import static org.atmosphere.cpr.HeaderConfig.ATMOSPHERE_POST_BODY;

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
    public static final String MAPPING_REGEX = "[a-zA-Z0-9-&.*=;\\?]+";

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
    protected final static EventLogger eLogger = new EventLogger();

    protected boolean useNativeImplementation = false;
    protected boolean useBlockingImplementation = false;
    protected boolean useStreamForFlushingComments = false;
    protected AsyncSupport asyncSupport;
    protected String broadcasterClassName = DefaultBroadcaster.class.getName();
    protected boolean isCometSupportSpecified = false;
    protected boolean isBroadcasterSpecified = false;
    protected boolean isSessionSupportSpecified = false;
    protected BroadcasterFactory broadcasterFactory;
    protected String broadcasterFactoryClassName;
    protected static String broadcasterCacheClassName;
    protected boolean webSocketEnabled = true;
    protected String broadcasterLifeCyclePolicy = "NEVER";
    protected String webSocketProtocolClassName = SimpleHttpProtocol.class.getName();
    protected WebSocketProtocol webSocketProtocol;
    protected String handlersPath = "/WEB-INF/classes/";
    protected ServletConfig servletConfig;
    protected boolean autoDetectHandlers = true;
    private boolean hasNewWebSocketProtocol = false;
    protected String atmosphereDotXmlPath = DEFAULT_ATMOSPHERE_CONFIG_PATH;
    protected final LinkedList<AtmosphereInterceptor> interceptors = new LinkedList<AtmosphereInterceptor>();
    protected boolean scanDone = false;
    protected String annotationProcessorClassName = "org.atmosphere.cpr.DefaultAnnotationProcessor";

    @Override
    public ServletContext getServletContext() {
        return servletConfig.getServletContext();
    }

    public ServletConfig getServletConfig() {
        return servletConfig;
    }

    public List<String> broadcasterFilters() {
        return broadcasterFilters;
    }

    public static final class AtmosphereHandlerWrapper {

        public final AtmosphereHandler atmosphereHandler;
        public Broadcaster broadcaster;
        public String mapping;
        public List<AtmosphereInterceptor> interceptors = Collections.<AtmosphereInterceptor>emptyList();

        public AtmosphereHandlerWrapper(AtmosphereHandler atmosphereHandler, String mapping) {
            this.atmosphereHandler = atmosphereHandler;
            try {
                if (BroadcasterFactory.getDefault() != null) {
                    this.broadcaster = BroadcasterFactory.getDefault().get(mapping);
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
     * Return a configured instance of {@link AtmosphereConfig}
     *
     * @return a configured instance of {@link AtmosphereConfig}
     */
    public AtmosphereConfig getAtmosphereConfig() {
        return config;
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
    }

    /**
     * Add an {@link AtmosphereHandler} serviced by the {@link Servlet}
     * This API is exposed to allow embedding an Atmosphere application.
     *
     * @param mapping The servlet mapping (servlet path)
     * @param h       implementation of an {@link AtmosphereHandler}
     * @param l       An attay of {@link AtmosphereInterceptor}
     */
    public AtmosphereFramework addAtmosphereHandler(String mapping, AtmosphereHandler h, List<AtmosphereInterceptor> l) {
        if (!mapping.startsWith("/")) {
            mapping = "/" + mapping;
        }

        AtmosphereHandlerWrapper w = new AtmosphereHandlerWrapper(h, mapping);
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
            path = path.replace("*", MAPPING_REGEX);
        }

        if (path.endsWith("/")) {
            path = path + MAPPING_REGEX;
        }

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

        AtmosphereHandlerWrapper w = new AtmosphereHandlerWrapper(h, mapping);
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
            mapping += MAPPING_REGEX;
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
     * Load the {@link AtmosphereHandler} associated with this AtmosphereServlet.
     *
     * @param sc the {@link ServletContext}
     */
    public AtmosphereFramework init(final ServletConfig sc) throws ServletException {
        try {
            ServletContextHolder.register(this);

            ServletConfig scFacade = new ServletConfig() {

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
                    Enumeration en = sc.getInitParameterNames();
                    while (en.hasMoreElements()) {
                        String name = (String) en.nextElement();
                        if (!initParams.containsKey(name)) {
                            initParams.put(name, sc.getInitParameter(name));
                        }
                    }
                    return Collections.enumeration(initParams.keySet());
                }
            };
            this.servletConfig = scFacade;
            asyncSupportListener(eLogger);

            autoConfigureService(scFacade.getServletContext());
            patchContainer();
            doInitParams(scFacade);
            doInitParamsForWebSocket(scFacade);
            configureBroadcaster();
            loadConfiguration(scFacade);

            autoDetectContainer();
            configureWebDotXmlAtmosphereHandler(sc);
            initWebSocketProtocol();
            asyncSupport.init(scFacade);
            initAtmosphereHandler(scFacade);
            configureAtmosphereInterceptor(sc);

            if (broadcasterCacheClassName == null) {
                logger.warn("No BroadcasterCache configured. Broadcasted message between client reconnection will be LOST. " +
                        "It is recommended to configure the HeaderBroadcasterCache.");
            }

            // http://java.net/jira/browse/ATMOSPHERE-157
            if (sc.getServletContext() != null) {
                sc.getServletContext().setAttribute(BroadcasterFactory.class.getName(), broadcasterFactory);
            }

            logger.info("Using BroadcasterFactory class: {}", BroadcasterFactory.getDefault().getClass().getName());
            logger.info("Using Broadcaster class: {}", broadcasterClassName);
            logger.info("Atmosphere Framework {} started.", Version.getRawVersion());
        } catch (Throwable t) {
            logger.error("Failed to initialize Atmosphere Framework", t);

            if (t instanceof ServletException) {
                throw (ServletException) t;
            }

            throw new ServletException(t);
        }
        return this;
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
                    ai.configure(config);
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

        s = sc.getInitParameter(ApplicationConfig.DISABLE_ATMOSPHEREINTERCEPTOR);
        if (s == null) {
            // ADD JSONP support
            interceptors.addFirst(new JSONPAtmosphereInterceptor());
            // Add SSE support
            interceptors.addFirst(new SSEAtmosphereInterceptor());
        }
        logger.info("Installed AtmosphereInterceptor {}", interceptors);
    }

    protected void configureWebDotXmlAtmosphereHandler(ServletConfig sc) {
        String s = sc.getInitParameter(ATMOSPHERE_HANDLER);
        if (s != null) {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            try {

                String mapping = sc.getInitParameter(ATMOSPHERE_HANDLER_MAPPING);
                if (mapping == null) {
                    mapping = "/*";
                }
                addAtmosphereHandler(mapping, (AtmosphereHandler) cl.loadClass(s).newInstance());
            } catch (Exception ex) {
                logger.warn("Unable to load WebSocketHandle instance", ex);
            }
        }
    }

    protected void configureBroadcaster() {

        try {
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

            BroadcasterFactory.setBroadcasterFactory(broadcasterFactory, config);
            InjectorProvider.getInjector().inject(broadcasterFactory);

            Iterator<Entry<String, AtmosphereHandlerWrapper>> i = atmosphereHandlers.entrySet().iterator();
            AtmosphereHandlerWrapper w;
            Entry<String, AtmosphereHandlerWrapper> e;
            while (i.hasNext()) {
                e = i.next();
                w = e.getValue();
                BroadcasterConfig broadcasterConfig = new BroadcasterConfig(broadcasterFilters, config);

                if (w.broadcaster == null) {
                    w.broadcaster = broadcasterFactory.get(w.mapping);
                } else {
                    w.broadcaster.setBroadcasterConfig(broadcasterConfig);
                    if (broadcasterCacheClassName != null) {
                        BroadcasterCache cache = (BroadcasterCache) Thread.currentThread().getContextClassLoader()
                                .loadClass(broadcasterCacheClassName).newInstance();
                        InjectorProvider.getInjector().inject(cache);
                        broadcasterConfig.setBroadcasterCache(cache);
                    }
                }
            }
        } catch (Exception ex) {
            logger.error("Unable to configure Broadcaster/Factory/Cache", ex);
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
            isSessionSupportSpecified = true;
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
                broadcasterClassNameTmp = lookupDefaultBroadcasterType();

                cl.loadClass(broadcasterClassNameTmp);
            }
            useStreamForFlushingComments = true;
        } catch (Throwable t) {
            logger.trace("", t);
            return false;
        }

        logger.warn("Missing META-INF/atmosphere.xml but found the Jersey runtime. Starting Jersey");

        // Jersey will handle itself the headers.
        initParams.put(WRITE_HEADERS, "false");

        ReflectorServletProcessor rsp = new ReflectorServletProcessor();
        if (broadcasterClassNameTmp != null) broadcasterClassName = broadcasterClassNameTmp;
        rsp.setServletClassName(JERSEY_CONTAINER);
        sessionSupport(false);
        initParams.put(DISABLE_ONSTATE_EVENT, "true");

        String mapping = sc.getInitParameter(PROPERTY_SERVLET_MAPPING);
        if (mapping == null) {
            mapping = "/*";
        }
        Class<? extends Broadcaster> bc = (Class<? extends Broadcaster>) cl.loadClass(broadcasterClassName);

        broadcasterFactory.destroy();

        broadcasterFactory = new DefaultBroadcasterFactory(bc, broadcasterLifeCyclePolicy, config);
        BroadcasterFactory.setBroadcasterFactory(broadcasterFactory, config);
        Broadcaster b = BroadcasterFactory.getDefault().get(bc, mapping);

        addAtmosphereHandler(mapping, rsp, b);
        return true;
    }

    protected String lookupDefaultBroadcasterType() {
        for (String b : broadcasterTypes) {
            try {
                Class.forName(b);
                return b;
            } catch (ClassNotFoundException e) {
            }
        }
        return JERSEY_BROADCASTER;
    }

    protected void sessionSupport(boolean sessionSupport) {
        if (!isSessionSupportSpecified) {
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
            addAtmosphereHandler("/*", new AbstractReflectorAtmosphereHandler() {
                @Override
                public void onRequest(AtmosphereResource httpServletRequestHttpServletResponseAtmosphereResource) throws IOException {
                }

                @Override
                public void destroy() {
                }
            });
        }
    }

    protected void initWebSocketProtocol() {
        if (webSocketProtocol == null) {
            try {
                webSocketProtocol = (WebSocketProtocol) AtmosphereFramework.class.getClassLoader()
                        .loadClass(webSocketProtocolClassName).newInstance();
                logger.info("Installed WebSocketProtocol {} ", webSocketProtocolClassName);
            } catch (Exception ex) {
                logger.error("Cannot load the WebSocketProtocol {}", getWebSocketProtocolClassName(), ex);
                webSocketProtocol = new SimpleHttpProtocol();
            }
        }
        webSocketProtocol.configure(config);
    }

    public AtmosphereFramework destroy() {
        if (asyncSupport != null && AsynchronousProcessor.class.isAssignableFrom(asyncSupport.getClass())) {
            ((AsynchronousProcessor) asyncSupport).shutdown();
        }

        // We just need one bc to shutdown the shared thread pool
        BroadcasterConfig bc = null;
        for (Entry<String, AtmosphereHandlerWrapper> entry : atmosphereHandlers.entrySet()) {
            AtmosphereHandlerWrapper handlerWrapper = entry.getValue();
            handlerWrapper.atmosphereHandler.destroy();
        }

        BroadcasterFactory factory = BroadcasterFactory.getDefault();
        if (factory != null) {
            factory.destroy();
            BroadcasterFactory.factory = null;
        }
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

        AtmosphereConfigReader.getInstance().parse(config, stream);
        for (AtmosphereHandlerConfig atmoHandler : config.getAtmosphereHandlerConfig()) {
            try {
                AtmosphereHandler handler;

                if (!ReflectorServletProcessor.class.getName().equals(atmoHandler.getClassName())) {
                    handler = (AtmosphereHandler) c.loadClass(atmoHandler.getClassName()).newInstance();
                    InjectorProvider.getInjector().inject(handler);
                } else {
                    handler = new ReflectorServletProcessor();
                }

                logger.info("Installed AtmosphereHandler {} mapped to context-path: {}", handler, atmoHandler.getContextRoot());

                for (ApplicationConfiguration a : atmoHandler.getApplicationConfig()) {
                    initParams.put(a.getParamName(), a.getParamValue());
                }

                for (FrameworkConfiguration a : atmoHandler.getFrameworkConfig()) {
                    initParams.put(a.getParamName(), a.getParamValue());
                }

                boolean isJersey = false;
                for (AtmosphereHandlerProperty handlerProperty : atmoHandler.getProperties()) {

                    if (handlerProperty.getValue() != null && handlerProperty.getValue().indexOf("jersey") != -1) {
                        isJersey = true;
                        initParams.put(DISABLE_ONSTATE_EVENT, "true");
                        useStreamForFlushingComments = true;
                        broadcasterClassName = lookupDefaultBroadcasterType();
                        broadcasterFactory = null;
                        configureBroadcaster();
                    }

                    IntrospectionUtils.setProperty(handler, handlerProperty.getName(), handlerProperty.getValue());
                    IntrospectionUtils.addProperty(handler, handlerProperty.getName(), handlerProperty.getValue());
                }

                config.setSupportSession(!isJersey);

                if (!atmoHandler.getSupportSession().equals("")) {
                    sessionSupport(Boolean.valueOf(atmoHandler.getSupportSession()));
                }

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

                b = BroadcasterFactory.getDefault().lookup(atmoHandler.getContextRoot(), true);

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
                            ai.configure(config);
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
                    .resolve(useNativeImplementation, useBlockingImplementation, webSocketEnabled));
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
                    className = className.replaceFirst("^.*/(WEB-INF|target)/(test-)?classes/(.*)\\.class", "$3").replace("/", ".");
                    Class<?> clazz = classloader.loadClass(className);

                    if (AtmosphereHandler.class.isAssignableFrom(clazz)) {
                        AtmosphereHandler handler = (AtmosphereHandler) clazz.newInstance();
                        InjectorProvider.getInjector().inject(handler);
                        addMapping("/" + handler.getClass().getSimpleName(),
                                new AtmosphereHandlerWrapper(handler, "/" + handler.getClass().getSimpleName()));
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
                    className = className.replaceFirst("^.*/(WEB-INF|target)/(test-)?classes/(.*)\\.class", "$3").replace("/", ".");
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
     * Invoke the proprietary {@link AsyncSupport}
     *
     * @param req
     * @param res
     * @return an {@link Action}
     * @throws IOException
     * @throws ServletException
     */
    public Action doCometSupport(AtmosphereRequest req, AtmosphereResponse res) throws IOException, ServletException {
        req.setAttribute(BROADCASTER_FACTORY, BroadcasterFactory.getDefault());
        req.setAttribute(PROPERTY_USE_STREAM, useStreamForFlushingComments);
        req.setAttribute(BROADCASTER_CLASS, broadcasterClassName);
        req.setAttribute(ATMOSPHERE_CONFIG, config);

        Action a = null;
        try {
            boolean skip = true;
            String s = config.getInitParameter(ALLOW_QUERYSTRING_AS_REQUEST);
            if (s != null) {
                skip = Boolean.valueOf(s);
            }
            if (!skip || req.getAttribute(WebSocket.WEBSOCKET_SUSPEND) == null) {
                Map<String, String> headers = configureQueryStringAsRequest(req);
                String body = headers.remove(ATMOSPHERE_POST_BODY);
                if (body != null && body.isEmpty()) {
                    body = null;
                }

                req.headers(headers)
                        .method(body != null && req.getMethod().equalsIgnoreCase("GET") ? "POST" : req.getMethod());

                if (body != null) {
                    req.body(body);
                }
            }
            a = asyncSupport.service(req, res);
        } catch (IllegalStateException ex) {
            if (ex.getMessage() != null && (ex.getMessage().startsWith("Tomcat failed") || ex.getMessage().startsWith("JBoss failed"))) {
                if (!isFilter) {
                    logger.warn("Failed using comet support: {}, error: {} Is the Nio or Apr Connector enabled?", asyncSupport.getClass().getName(),
                            ex.getMessage());
                }
                logger.trace(ex.getMessage(), ex);

                asyncSupport = asyncSupport.supportWebSocket() ? new Tomcat7Servlet30SupportWithWebSocket(config) : new BlockingIOCometSupport(config);
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
        return null;
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
        broadcasterClassName = bccn;
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
    public void setBroadcasterCacheClassName(String broadcasterCacheClassName) {
        this.broadcasterCacheClassName = broadcasterCacheClassName;
        configureBroadcaster();
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

        Enumeration<String> e = request.getParameterNames();
        String s;
        while (e.hasMoreElements()) {
            s = e.nextElement();
            headers.put(s, request.getParameter(s));
        }
        return headers;
    }

    protected boolean isIECandidate(AtmosphereRequest request) {
        String userAgent = request.getHeader("User-Agent");
        if (userAgent == null) return false;

        if (userAgent.contains("MSIE") || userAgent.contains(".NET")) {
            // Now check the header
            String transport = request.getHeader(HeaderConfig.X_ATMOSPHERE_TRANSPORT);
            if (transport != null) {
                return false;
            } else {
                return true;
            }
        }
        return false;
    }

    public WebSocketProtocol getWebSocketProtocol() {
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
            interceptors.addLast(c);
        }
        return this;
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

    protected void autoConfigureService(ServletContext sc) throws IOException {
        final ClassLoader cl = Thread.currentThread().getContextClassLoader();

        String path = sc.getRealPath(handlersPath);
        try {
            AnnotationProcessor p = (AnnotationProcessor) cl.loadClass(annotationProcessorClassName).newInstance();
            p.configure(this).scan(new File(path));

            String pathLibs = sc.getRealPath(DEFAULT_LIB_PATH);
            File libFolder = new File(pathLibs);
            File jars[] = libFolder.listFiles(new FilenameFilter() {

                @Override
                public boolean accept(File arg0, String arg1) {
                    return arg1.endsWith(".jar");
                }
            });

            for (File file : jars) {
                p.scan(file);
            }
        } catch (Throwable e) {
            logger.debug("Atmosphere's Service Annotation Not Supported. Please add https://github.com/rmuller/infomas-asl as dependencies or your own AnnotationProcessor to support @Service");
            logger.trace("", e);
            return;
        }
    }

    protected void notify(Action.TYPE type, AtmosphereRequest request, AtmosphereResponse response) {
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
                        l.onSuspend(request, response);
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

    private final static class EventLogger implements AsyncSupportListener {
        @Override
        public void onSuspend(AtmosphereRequest request, AtmosphereResponse response) {
            logger.trace("Suspended request {} and response {}", request, response);
        }

        @Override
        public void onResume(AtmosphereRequest request, AtmosphereResponse response) {
            logger.trace("Suspended request {} and response {}", request, response);
        }

        @Override
        public void onTimeout(AtmosphereRequest request, AtmosphereResponse response) {
            logger.trace("Suspended request {} and response {}", request, response);
        }

        @Override
        public void onClose(AtmosphereRequest request, AtmosphereResponse response) {
            logger.trace("Suspended request {} and response {}", request, response);
        }

        @Override
        public void onDestroyed(AtmosphereRequest request, AtmosphereResponse response) {
            logger.trace("Suspended request {} and response {}", request, response);
        }
    }

}
