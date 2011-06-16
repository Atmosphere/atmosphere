/*
 *
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 2007-2008 Sun Microsystems, Inc. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License. You can obtain
 * a copy of the License at https://glassfish.dev.java.net/public/CDDL+GPL.html
 * or glassfish/bootstrap/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/bootstrap/legal/LICENSE.txt.
 * Sun designates this particular file as subject to the "Classpath" exception
 * as provided by Sun in the GPL Version 2 section of the License file that
 * accompanied this code.  If applicable, add the following below the License
 * Header, with the fields enclosed by brackets [] replaced by your own
 * identifying information: "Portions Copyrighted [year]
 * [name of copyright owner]"
 *
 * Contributor(s):
 *
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 *
 */

package org.atmosphere.cpr;

import org.apache.catalina.CometEvent;
import org.apache.catalina.CometProcessor;
import org.atmosphere.container.BlockingIOCometSupport;
import org.atmosphere.container.GoogleAppEngineCometSupport;
import org.atmosphere.container.JBossWebCometSupport;
import org.atmosphere.container.TomcatCometSupport;
import org.atmosphere.container.WebLogicCometSupport;
import org.atmosphere.di.InjectorProvider;
import org.atmosphere.di.ServletContextHolder;
import org.atmosphere.di.ServletContextProvider;
import org.atmosphere.handler.ReflectorServletProcessor;
import org.atmosphere.util.AtmosphereConfigReader;
import org.atmosphere.util.AtmosphereConfigReader.Property;
import org.atmosphere.util.IntrospectionUtils;
import org.atmosphere.util.Version;
import org.atmosphere.util.gae.GAEDefaultBroadcaster;
import org.atmosphere.websocket.JettyWebSocketSupport;
import org.atmosphere.websocket.WebSocketAtmosphereHandler;
import org.eclipse.jetty.websocket.WebSocket;
import org.jboss.servlet.http.HttpEvent;
import org.jboss.servlet.http.HttpEventServlet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import weblogic.servlet.http.AbstractAsyncServlet;
import weblogic.servlet.http.RequestResponseKey;

import javax.servlet.Servlet;
import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The {@link AtmosphereServlet} acts as a dispatcher for {@link AtmosphereHandler}
 * defined in META-INF/atmosphere.xml, or if atmosphere.xml is missing, all classes
 * that implements {@link AtmosphereHandler} will be discovered and mapped using
 * the class's name.
 * <p/>
 * This {@link Servlet} can be defined inside an application's web.xml using the following:
 * <p><pre><code>
 *  &lt;servlet&gt;
 *      &lt;description&gt;AtmosphereServlet&lt;/description&gt;
 *      &lt;servlet-name&gt;AtmosphereServlet&lt;/servlet-name&gt;
 *      &lt;servlet-class&gt;org.atmosphere.cpr.AtmosphereServlet&lt;/servlet-class&gt;
 *      &lt;load-on-startup&gt;0 &lt;/load-on-startup&gt;
 *  &lt;/servlet&gt;
 *  &lt;servlet-mapping&gt;
 *      &lt;servlet-name&gt;AtmosphereServlet&lt;/servlet-name&gt;
 *      &lt;url-pattern&gt;/Atmosphere &lt;/url-pattern&gt;
 *  &lt;/servlet-mapping&gt;
 * </code></pre></p>
 * You can force this Servlet to use native API of the Web Server instead of
 * the Servlet 3.0 Async API you are deploying on by adding
 * <p><pre><code>
 *  &lt;init-param&gt;
 *      &lt;param-name&gt;org.atmosphere.useNative&lt;/param-name&gt;
 *      &lt;param-value&gt;true&lt;/param-value&gt;
 *  &lt;/init-param&gt;
 * </code></pre></p>
 * You can force this Servlet to use one Thread per connection instead of
 * native API of the Web Server you are deploying on by adding
 * <p><pre><code>
 *  &lt;init-param&gt;
 *      &lt;param-name&gt;org.atmosphere.useBlocking&lt;/param-name&gt;
 *      &lt;param-value&gt;true&lt;/param-value&gt;
 *  &lt;/init-param&gt;
 * </code></pre></p>
 * You can also define {@link Broadcaster}by adding:
 * <p><pre><code>
 *  &lt;init-param&gt;
 *      &lt;param-name&gt;org.atmosphere.cpr.broadcasterClass&lt;/param-name&gt;
 *      &lt;param-value&gt;class-name&lt;/param-value&gt;
 *  &lt;/init-param&gt;
 * </code></pre></p>
 * You can also for Atmosphere to use {@link java.io.OutputStream} for all write operations.
 * <p><pre><code>
 *  &lt;init-param&gt;
 *      &lt;param-name&gt;org.atmosphere.useStream&lt;/param-name&gt;
 *      &lt;param-value&gt;true&lt;/param-value&gt;
 *  &lt;/init-param&gt;
 * </code></pre></p>
 * You can also configure {@link org.atmosphere.cpr.BroadcasterCache} that persist message when Browser is disconnected.
 * <p><pre><code>
 *  &lt;init-param&gt;
 *      &lt;param-name&gt;org.atmosphere.cpr.broadcasterCacheClass&lt;/param-name&gt;
 *      &lt;param-value&gt;class-name&lt;/param-value&gt;
 *  &lt;/init-param&gt;
 * </code></pre></p>
 * You can also configure Atmosphere to use http session or not
 * <p><pre><code>
 *  &lt;init-param&gt;
 *      &lt;param-name&gt;org.atmosphere.cpr.sessionSupport&lt;/param-name&gt;
 *      &lt;param-value&gt;false&lt;/param-value&gt;
 *  &lt;/init-param&gt;
 * </code></pre></p>
 * You can also configure {@link BroadcastFilter} that will be applied at all newly created {@link Broadcaster}
 * <p><pre><code>
 *  &lt;init-param&gt;
 *      &lt;param-name&gt;org.atmosphere.cpr.broadcastFilterClasses&lt;/param-name&gt;
 *      &lt;param-value&gt;BroadcastFilter class name separated by coma&lt;/param-value&gt;
 *  &lt;/init-param&gt;
 * </code></pre></p>
 * The Atmosphere Framework can also be used as a Servlet Filter ({@link AtmosphereFilter}).
 * <p/>
 * If you are planning to use JSP, Servlet or JSF, you can instead use the
 * {@link MeteorServlet}, which allow the use of {@link Meteor} inside those
 * components.
 *
 * @author Jeanfrancois Arcand
 */
public class AtmosphereServlet extends AbstractAsyncServlet implements CometProcessor, HttpEventServlet, ServletContextProvider {

    private static final Logger logger = LoggerFactory.getLogger(AtmosphereServlet.class);

    public final static String JERSEY_BROADCASTER = "org.atmosphere.jersey.JerseyBroadcaster";
    public final static String REDIS_BROADCASTER = "org.atmosphere.plugin.redis.RedisBroadcaster";
    public final static String JMS_BROADCASTER = "org.atmosphere.plugin.jms.JMSBroadcaster";
    public final static String JGROUPS_BROADCASTER = "org.atmosphere.plugin.jgroups.JGroupsBroadcaster";
    public final static String XMPP_BROADCASTER = "org.atmosphere.plugin.xmpp.XMPPBroadcaster";

    public final static String JERSEY_CONTAINER = "com.sun.jersey.spi.container.servlet.ServletContainer";
    public final static String GAE_BROADCASTER = GAEDefaultBroadcaster.class.getName();
    public final static String PROPERTY_SERVLET_MAPPING = "org.atmosphere.jersey.servlet-mapping";
    public final static String PROPERTY_BLOCKING_COMETSUPPORT = "org.atmosphere.useBlocking";
    public final static String PROPERTY_NATIVE_COMETSUPPORT = "org.atmosphere.useNative";
    public final static String WEBSOCKET_SUPPORT = "org.atmosphere.useWebSocket";
    public final static String PROPERTY_USE_STREAM = "org.atmosphere.useStream";
    public final static String BROADCASTER_FACTORY = "org.atmosphere.cpr.broadcasterFactory";
    public final static String BROADCASTER_CLASS = "org.atmosphere.cpr.broadcasterClass";
    public final static String BROADCASTER_CACHE = "org.atmosphere.cpr.broadcasterCacheClass";
    public final static String PROPERTY_COMET_SUPPORT = "org.atmosphere.cpr.cometSupport";
    public final static String PROPERTY_SESSION_SUPPORT = "org.atmosphere.cpr.sessionSupport";
    public final static String PRIMEFACES_SERVLET = "org.primefaces.comet.PrimeFacesCometServlet";
    public final static String DISABLE_ONSTATE_EVENT = "org.atmosphere.disableOnStateEvent";
    public final static String WEB_INF_CLASSES = "/WEB-INF/classes/";
    public final static String RESUME_ON_BROADCAST = "org.atmosphere.resumeOnBroadcast";
    public final static String ATMOSPHERE_SERVLET = AtmosphereServlet.class.getName();
    public final static String ATMOSPHERE_RESOURCE = AtmosphereResource.class.getName();
    public final static String SUPPORT_SESSION = "org.atmosphere.cpr.AsynchronousProcessor.supportSession";
    public final static String ATMOSPHERE_HANDLER = AtmosphereHandler.class.getName();
    public final static String WEBSOCKET_ATMOSPHEREHANDLER = WebSocketAtmosphereHandler.class.getName();
    public final static String RESUME_AND_KEEPALIVE = AtmosphereServlet.class.getName() + ".resumeAndKeepAlive";
    public final static String RESUMED_ON_TIMEOUT = AtmosphereServlet.class.getName() + ".resumedOnTimeout";
    public final static String DEFAULT_NAMED_DISPATCHER = "default";
    public final static String BROADCAST_FILTER_CLASSES = "org.atmosphere.cpr.broadcastFilterClasses";
    public final static String NO_CACHE_HEADERS = "org.atmosphere.cpr.noCacheHeaders";
    public final static String DROP_ACCESS_CONTROL_ALLOW_ORIGIN_HEADER = "org.atmosphere.cpr.dropAccessControlAllowOriginHeader";
    public final static String CONTAINER_RESPONSE = "org.atmosphere.jersey.containerResponse";
    public final static String BROADCASTER_LIFECYCLE_POLICY = "org.atmosphere.cpr.broadcasterLifeCyclePolicy";


    private static final AtmospherePingSupport ATMOSPHERE_PING_SUPPORT = new AtmospherePingSupport();

    private final ArrayList<String> possibleAtmosphereHandlersCandidate = new ArrayList<String>();
    private final HashMap<String, String> initParams = new HashMap<String, String>();
    protected final AtmosphereConfig config = new AtmosphereConfig();
    protected final AtomicBoolean isCometSupportConfigured = new AtomicBoolean(false);
    protected final boolean isFilter;
    public static String[] broadcasterFilters = new String[0];

    /**
     * The list of {@link AtmosphereHandler} and their associated mapping.
     */
    private final Map<String, AtmosphereHandlerWrapper> atmosphereHandlers =
            new ConcurrentHashMap<String, AtmosphereHandlerWrapper>();

    private final ConcurrentLinkedQueue<String> broadcasterTypes = new ConcurrentLinkedQueue<String>();

    // If we detect Servlet 3.0, should we still use the default
    // native Comet API.
    protected boolean useNativeImplementation = false;
    protected boolean useBlockingImplementation = false;
    protected boolean useStreamForFlushingComments = false;
    protected CometSupport cometSupport;
    protected static String broadcasterClassName = DefaultBroadcaster.class.getName();
    protected boolean isCometSupportSpecified = false;
    protected boolean isBroadcasterSpecified = false;
    protected boolean isSessionSupportSpecified = false;
    private BroadcasterFactory broadcasterFactory;
    protected static String broadcasterCacheClassName;
    private boolean webSocketEnabled = false;
    private String broadcasterLifeCyclePolicy = "NEVER";

    public static final class AtmosphereHandlerWrapper {

        public final AtmosphereHandler atmosphereHandler;
        public Broadcaster broadcaster;

        public AtmosphereHandlerWrapper(AtmosphereHandler atmosphereHandler) {
            this.atmosphereHandler = atmosphereHandler;
            try {
                broadcaster = BroadcasterFactory.getDefault().get();
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

    public class AtmosphereConfig {

        private boolean supportSession = true;
        private BroadcasterFactory broadcasterFactory;
        private String dispatcherName = DEFAULT_NAMED_DISPATCHER;

        protected Map<String, AtmosphereHandlerWrapper> handlers() {
            return AtmosphereServlet.this.atmosphereHandlers;
        }

        public ServletContext getServletContext() {
            return AtmosphereServlet.this.getServletContext();
        }

        public String getDispatcherName() {
            return dispatcherName;
        }

        public void setDispatcherName(String dispatcherName) {
            this.dispatcherName = dispatcherName;
        }

        public String getInitParameter(String name) {
            // First looks locally
            String s = initParams.get(name);
            if (s != null) {
                return s;
            }

            return AtmosphereServlet.this.getInitParameter(name);
        }

        public Enumeration getInitParameterNames() {
            return AtmosphereServlet.this.getInitParameterNames();
        }

        public ServletConfig getServletConfig() {
            return AtmosphereServlet.this.getServletConfig();
        }

        public String getWebServerName() {
            return AtmosphereServlet.this.cometSupport.getContainerName();
        }

        /**
         * Return an instance of a {@link DefaultBroadcasterFactory}
         *
         * @return an instance of a {@link DefaultBroadcasterFactory}
         */
        public BroadcasterFactory getBroadcasterFactory() {
            return broadcasterFactory;
        }

        public boolean isSupportSession() {
            return supportSession;
        }

        public void setSupportSession(boolean supportSession) {
            this.supportSession = supportSession;
        }

        public AtmosphereServlet getServlet() {
            return AtmosphereServlet.this;
        }
    }

    /**
     * Simple class/struck that hold the current state.
     */
    public static class Action {

        public enum TYPE {
            SUSPEND, RESUME, TIMEOUT, CANCELLED, KEEP_ALIVED
        }

        public long timeout = -1L;

        public TYPE type;

        public Action() {
            type = TYPE.CANCELLED;
        }

        public Action(TYPE type) {
            this.type = type;
        }

        public Action(TYPE type, long timeout) {
            this.timeout = timeout;
            this.type = type;
        }
    }

    /**
     * Create an Atmosphere Servlet.
     */
    public AtmosphereServlet() {
        this(false);
    }

    /**
     * Create an Atmosphere Servlet.
     *
     * @param isFilter true if this instance is used as an {@link AtmosphereFilter}
     */
    public AtmosphereServlet(boolean isFilter) {
        this.isFilter = isFilter;
        readSystemProperties();
        populateBroadcasterType();
    }


    /**
     * Configure the {@link org.atmosphere.cpr.BroadcasterFactory}
     */
    protected void configureDefaultBroadcasterFactory() {
        Class<? extends Broadcaster> b = null;
        String defaultBroadcasterClassName = AtmosphereServlet.getDefaultBroadcasterClassName();

        try {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            b = (Class<? extends Broadcaster>) cl.loadClass(defaultBroadcasterClassName);
        }
        catch (ClassNotFoundException e) {
            logger.error("failed to load default broadcaster class name: " + defaultBroadcasterClassName, e);
        }

        Class bc = (b == null ? DefaultBroadcaster.class : b);
        logger.info("using default broadcaster class: {}", bc);
        BroadcasterFactory.setBroadcasterFactory(new DefaultBroadcasterFactory(bc, broadcasterLifeCyclePolicy), config);
    }


    /**
     * The order of addition is quite important here.
     */
    private void populateBroadcasterType() {
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
     */
    public void addAtmosphereHandler(String mapping, AtmosphereHandler h) {
        if (!mapping.startsWith("/")) {
            mapping = "/" + mapping;
        }

        AtmosphereHandlerWrapper w = new AtmosphereHandlerWrapper(h);
        atmosphereHandlers.put(mapping, w);
    }

    /**
     * Add an {@link AtmosphereHandler} serviced by the {@link Servlet}
     * This API is exposed to allow embedding an Atmosphere application.
     *
     * @param mapping       The servlet mapping (servlet path)
     * @param h             implementation of an {@link AtmosphereHandler}
     * @param broadcasterId The {@link Broadcaster#getID} value.
     */
    public void addAtmosphereHandler(String mapping, AtmosphereHandler h, String broadcasterId) {
        if (!mapping.startsWith("/")) {
            mapping = "/" + mapping;
        }

        AtmosphereHandlerWrapper w = new AtmosphereHandlerWrapper(h);
        w.broadcaster.setID(broadcasterId);
        atmosphereHandlers.put(mapping, w);
    }

    /**
     * Add an {@link AtmosphereHandler} serviced by the {@link Servlet}
     * This API is exposed to allow embedding an Atmosphere application.
     *
     * @param mapping     The servlet mapping (servlet path)
     * @param h           implementation of an {@link AtmosphereHandler}
     * @param broadcaster The {@link Broadcaster} associated with AtmosphereHandler.
     */
    public void addAtmosphereHandler(String mapping, AtmosphereHandler<HttpServletRequest, HttpServletResponse> h, Broadcaster broadcaster) {
        if (!mapping.startsWith("/")) {
            mapping = "/" + mapping;
        }

        AtmosphereHandlerWrapper w = new AtmosphereHandlerWrapper(h, broadcaster);
        atmosphereHandlers.put(mapping, w);
    }

    /**
     * Remove an {@link AtmosphereHandler}
     *
     * @param mapping the mapping used when invoking {@link #addAtmosphereHandler(String, AtmosphereHandler)};
     * @return true if removed
     */
    public boolean removeAtmosphereHandler(String mapping) {
        return atmosphereHandlers.remove(mapping) == null ? false : true;
    }

    /**
     * Remove all {@link AtmosphereHandler}
     */
    public void removeAllAtmosphereHandler() {
        atmosphereHandlers.clear();
    }

    /**
     * Remove all init parameters.
     */
    public void removeAllInitParams() {
        initParams.clear();
    }

    /**
     * Add init-param like if they were defined in web.xml
     *
     * @param name  The name
     * @param value The value
     */
    public void addInitParameter(String name, String value) {
        initParams.put(name, value);
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

        if (System.getProperty(DISABLE_ONSTATE_EVENT) != null) {
            initParams.put(DISABLE_ONSTATE_EVENT, System.getProperty(DISABLE_ONSTATE_EVENT));
        }
    }

    /**
     * Load the {@link AtmosphereHandler} associated with this AtmosphereServlet.
     *
     * @param sc the {@link ServletContext}
     */
    @Override
    public void init(final ServletConfig sc) throws ServletException {
        logger.info("initializing atmosphere framework: {}", Version.getRawVersion());

        try {
            super.init(sc);

            ServletContextHolder.register(this);

            ServletConfig scFacade = new ServletConfig() {

                public String getServletName() {
                    return sc.getServletName();
                }

                public ServletContext getServletContext() {
                    return sc.getServletContext();
                }

                public String getInitParameter(String name) {
                    String param = sc.getInitParameter(name);
                    if (param == null) {
                        return initParams.get(name);
                    }
                    return param;
                }

                public Enumeration<String> getInitParameterNames() {
                    return sc.getInitParameterNames();
                }
            };
            pingForStats();
            doInitParams(scFacade);
            configureDefaultBroadcasterFactory();
            doInitParamsForWebSocket(scFacade);
            detectGoogleAppEngine(scFacade);
            loadConfiguration(scFacade);

            autoDetectContainer();
            configureBroadcaster();
            cometSupport.init(scFacade);
            initAtmosphereServletProcessor(scFacade);

            logger.info("started atmosphere framework: {}", Version.getRawVersion());
        }
        catch (Throwable t) {
            logger.error("failed to initialize atmosphere framework", t);

            if (t instanceof ServletException) {
                throw (ServletException) t;
            }

            throw new ServletException(t.getCause());
        }
    }

    protected void configureBroadcaster() throws ClassNotFoundException, InstantiationException, IllegalAccessException {

        if (broadcasterFactory == null) {
            Class<? extends Broadcaster> bc =
                    (Class<? extends Broadcaster>) Thread.currentThread().getContextClassLoader()
                            .loadClass(broadcasterClassName);
            logger.info("using broadcaster class: {}", bc.getName());

            broadcasterFactory = new DefaultBroadcasterFactory(bc, broadcasterLifeCyclePolicy);
            config.broadcasterFactory = broadcasterFactory;
            BroadcasterFactory.setBroadcasterFactory(broadcasterFactory, config);
        }

        Iterator<Entry<String, AtmosphereHandlerWrapper>> i = atmosphereHandlers.entrySet().iterator();
        AtmosphereHandlerWrapper w;
        Entry<String, AtmosphereHandlerWrapper> e;
        while (i.hasNext()) {
            e = i.next();
            w = e.getValue();
            BroadcasterConfig broadcasterConfig = new BroadcasterConfig(broadcasterFilters, config);

            if (w.broadcaster == null) {
                w.broadcaster = broadcasterFactory.get();
            } else {
                w.broadcaster.setBroadcasterConfig(broadcasterConfig);
                if (broadcasterCacheClassName != null) {
                    BroadcasterCache cache = (BroadcasterCache) Thread.currentThread().getContextClassLoader()
                            .loadClass(broadcasterCacheClassName).newInstance();
                    InjectorProvider.getInjector().inject(cache);
                    broadcasterConfig.setBroadcasterCache(cache);
                }
            }
            w.broadcaster.setID(e.getKey());
        }
    }

    protected void doInitParamsForWebSocket(ServletConfig sc) {
        String s = sc.getInitParameter(WEBSOCKET_ATMOSPHEREHANDLER);
        if (s != null) {
            addAtmosphereHandler("/*", new WebSocketAtmosphereHandler());
            webSocketEnabled = true;
            sessionSupport(false);
        }
        s = sc.getInitParameter(WEBSOCKET_SUPPORT);
        if (s != null) {
            webSocketEnabled = true;
            sessionSupport(false);
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
            cometSupport = new DefaultCometSupportResolver(config).newCometSupport(s);
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
            config.supportSession = Boolean.valueOf(s);
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
            broadcasterFilters = s.split(",");
        }
        s = sc.getInitParameter(BROADCASTER_LIFECYCLE_POLICY);
        if (s != null) {
            broadcasterLifeCyclePolicy = s;
        }
    }

    protected void loadConfiguration(ServletConfig sc) throws ServletException {
        try {
            URL url = sc.getServletContext().getResource("/WEB-INF/classes/");
            URLClassLoader urlC = new URLClassLoader(new URL[]{url},
                    Thread.currentThread().getContextClassLoader());
            loadAtmosphereDotXml(sc.getServletContext().
                    getResourceAsStream("/META-INF/atmosphere.xml"), urlC);

            if (atmosphereHandlers.size() == 0) {
                autoDetectAtmosphereHandlers(sc.getServletContext(), urlC);

                if (atmosphereHandlers.size() == 0) {
                    detectSupportedFramework(sc);
                }
            }
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

        // If Primefaces is detected, never start Jersey.
        // TODO: Remove this hack once properly implemented in PrimeFaces
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        try {
            cl.loadClass(PRIMEFACES_SERVLET);
            return false;
        } catch (Throwable ignored) {
        }

        try {
            cl.loadClass(JERSEY_CONTAINER);
            useStreamForFlushingComments = true;
        } catch (Throwable t) {
            return false;
        }

        logger.warn("Missing META-INF/atmosphere.xml but found the Jersey runtime. Starting Jersey");

        ReflectorServletProcessor rsp = new ReflectorServletProcessor();
        if (!isBroadcasterSpecified) broadcasterClassName = lookupDefaultBroadcasterType();
        rsp.setServletClassName(JERSEY_CONTAINER);
        sessionSupport(false);
        initParams.put(DISABLE_ONSTATE_EVENT, "true");

        String mapping = sc.getInitParameter(PROPERTY_SERVLET_MAPPING);
        if (mapping == null) {
            mapping = "/*";
        }
        Class<? extends Broadcaster> bc = (Class<? extends Broadcaster>) cl.loadClass(broadcasterClassName);

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
            config.supportSession = sessionSupport;
        }
    }

    /**
     * Auto-Detect Google App Engine.
     *
     * @param sc (@link ServletConfig}
     * @return true if detected
     */
    boolean detectGoogleAppEngine(ServletConfig sc) {
        if (sc.getServletContext().getServerInfo().startsWith("Google")) {
            broadcasterClassName = GAE_BROADCASTER;
            isBroadcasterSpecified = true;
            cometSupport = new GoogleAppEngineCometSupport(config);
            return true;
        } else {
            return false;
        }
    }

    /**
     * Initialize {@link AtmosphereServletProcessor}
     *
     * @param sc the {@link ServletConfig}
     * @throws javax.servlet.ServletException
     */
    void initAtmosphereServletProcessor(ServletConfig sc) throws ServletException {
        AtmosphereHandler a;
        for (Entry<String, AtmosphereHandlerWrapper> h : atmosphereHandlers.entrySet()) {
            a = h.getValue().atmosphereHandler;
            if (a instanceof AtmosphereServletProcessor) {
                ((AtmosphereServletProcessor) a).init(sc);
            }
        }
    }

    @Override
    public void destroy() {
        if (cometSupport != null && AsynchronousProcessor.class.isAssignableFrom(cometSupport.getClass())) {
            ((AsynchronousProcessor) cometSupport).shutdown();
        }

        for (Entry<String, AtmosphereHandlerWrapper> entry : atmosphereHandlers.entrySet()) {
            AtmosphereHandlerWrapper handlerWrapper = entry.getValue();
            handlerWrapper.atmosphereHandler.destroy();

            Broadcaster broadcaster = handlerWrapper.broadcaster;
            if (broadcaster != null) {
                broadcaster.destroy();
            }
        }

        BroadcasterFactory factory = BroadcasterFactory.getDefault();
        if (factory != null) {
            factory.destroy();
            BroadcasterFactory.factory = null;
        }
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

        AtmosphereConfigReader reader = new AtmosphereConfigReader(stream);

        Map<String, String> atmosphereHandlerNames = reader.getAtmosphereHandlers();
        Set<Entry<String, String>> entries = atmosphereHandlerNames.entrySet();
        for (Entry<String, String> entry : entries) {
            AtmosphereHandler handler;
            String handlerClassName = entry.getValue();
            String handlerPath = entry.getKey();

            try {
                if (!handlerClassName.equals(ReflectorServletProcessor.class.getName())) {
                    handler = (AtmosphereHandler) c.loadClass(handlerClassName).newInstance();
                    InjectorProvider.getInjector().inject(handler);
                } else {
                    handler = new ReflectorServletProcessor();
                }

                logger.info("successfully loaded handler: {} mapped to context-path: {}", handler, handlerPath);

                AtmosphereHandlerWrapper wrapper = new AtmosphereHandlerWrapper(handler);
                atmosphereHandlers.put(handlerPath, wrapper);
                boolean isJersey = false;
                for (Property p : reader.getProperty(handlerPath)) {
                    if (p.value != null && p.value.indexOf("jersey") != -1) {
                        isJersey = true;
                        initParams.put(DISABLE_ONSTATE_EVENT, "true");
                        useStreamForFlushingComments = true;
                    }
                    IntrospectionUtils.setProperty(handler, p.name, p.value);
                }

                config.supportSession = !isJersey;

                if (!reader.supportSession().equals("")) {
                    sessionSupport(Boolean.valueOf(reader.supportSession()));
                }

                for (Property p : reader.getProperty(handlerPath)) {
                    IntrospectionUtils.addProperty(handler, p.name, p.value);
                }

                String broadcasterClass = reader.getBroadcasterClass(handlerPath);
                /**
                 * If there is more than one AtmosphereHandler defined, their Broadcaster
                 * may clash each other with the BroadcasterFactory. In that case we will use the
                 * last one defined.
                 */
                if (broadcasterClass != null) {
                    broadcasterClassName = broadcasterClass;
                    ClassLoader cl = Thread.currentThread().getContextClassLoader();
                    Class<? extends Broadcaster> bc = (Class<? extends Broadcaster>) cl.loadClass(broadcasterClassName);
                    wrapper.broadcaster = BroadcasterFactory.getDefault().get(bc, handlerPath);
                }

                String bc = reader.getBroadcasterCache(handlerPath);
                if (bc != null) {
                    broadcasterCacheClassName = bc;
                }

                if (reader.getCometSupportClass() != null) {
                    cometSupport = (CometSupport) c.loadClass(reader.getCometSupportClass())
                            .getDeclaredConstructor(new Class[]{AtmosphereConfig.class})
                            .newInstance(new Object[]{config});
                }

                if (reader.getBroadcastFilterClasses() != null) {
                    broadcasterFilters = reader.getBroadcastFilterClasses();
                }

            }
            catch (Throwable t) {
                logger.warn("unable to load AtmosphereHandler class: " + handlerClassName, t);
                throw new ServletException(t);
            }
        }
    }

    /**
     * Set the {@link CometSupport} implementation. Make sure you don't set
     * an implementation that only works on some Container. See {@link BlockingIOCometSupport}
     * for an example.
     *
     * @param cometSupport
     */
    public void setCometSupport(CometSupport cometSupport) {
        this.cometSupport = cometSupport;
    }

    /**
     * Return the current {@link CometSupport}
     *
     * @return the current {@link CometSupport}
     */
    public CometSupport getCometSupport() {
        return cometSupport;
    }

    /**
     * Returns an instance of CometSupportResolver {@link CometSupportResolver}
     *
     * @return CometSupportResolver
     */
    protected CometSupportResolver createCometSupportResolver() {
        return new DefaultCometSupportResolver(config);
    }


    /**
     * Auto detect the underlying Servlet Container we are running on.
     */
    protected void autoDetectContainer() {
        // Was defined in atmosphere.xml
        if (getCometSupport() == null) {
            setCometSupport(createCometSupportResolver()
                    .resolve(useNativeImplementation, useBlockingImplementation, webSocketEnabled));
        }

        logger.info("Atmosphere is using comet support: {} running under container: {}",
                getCometSupport().getClass().getName(), cometSupport.getContainerName());
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
    protected void autoDetectAtmosphereHandlers(ServletContext servletContext, URLClassLoader classloader)
            throws MalformedURLException, URISyntaxException {
        logger.info("auto detecting atmosphere handlers in WEB-INF/classes");

        String realPath = servletContext.getRealPath(WEB_INF_CLASSES);

        // Weblogic bug
        if (realPath == null) {
            URL u = servletContext.getResource(WEB_INF_CLASSES);
            if (u == null) return;
            realPath = u.getPath();
        }

        loadAtmosphereHandlersFromPath(classloader, realPath);

        logger.info("Atmosphere using Broadcaster: {} ", broadcasterClassName);
    }

    protected void loadAtmosphereHandlersFromPath(URLClassLoader classloader, String realPath) {
        File file = new File(realPath);

        if (file.isDirectory()) {
            getFiles(file);

            for (String className : possibleAtmosphereHandlersCandidate) {
                try {
                    className = className.replace('\\', '/');
                    className = className.replaceFirst("^.*/(WEB-INF|target)/(test-)?classes/(.*)\\.class", "$3").replace("/",".");
                    Class<?> clazz = classloader.loadClass(className);

                    if (AtmosphereHandler.class.isAssignableFrom(clazz)) {
                        AtmosphereHandler handler = (AtmosphereHandler) clazz.newInstance();
                        InjectorProvider.getInjector().inject(handler);
                        atmosphereHandlers.put("/" + handler.getClass().getSimpleName(),
                                new AtmosphereHandlerWrapper(handler, null));
                        logger.info("Successfully loaded handler: {}  mapped to context-path: {}", handler,
                                handler.getClass().getSimpleName());
                    }
                }
                catch (Throwable t) {
                    logger.trace("failed to load class as an AtmosphereHandler: " + className, t);
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
        File[] files = f.listFiles();
        for (File test : files) {
            if (test.isDirectory()) {
                getFiles(test);
            } else {
                String clazz = test.getAbsolutePath();
                if (clazz.endsWith(".class")) {
                    possibleAtmosphereHandlersCandidate.add(clazz);
                }
            }
        }
    }

    /**
     * Delegate the request processing to an instance of {@link CometSupport}
     *
     * @param req the {@link HttpServletRequest}
     * @param res the {@link HttpServletResponse}
     * @throws java.io.IOException
     * @throws javax.servlet.ServletException
     */
    @Override
    public void doHead(HttpServletRequest req, HttpServletResponse res)
            throws IOException, ServletException {
        doPost(req, res);
    }

    /**
     * Delegate the request processing to an instance of {@link CometSupport}
     *
     * @param req the {@link HttpServletRequest}
     * @param res the {@link HttpServletResponse}
     * @throws java.io.IOException
     * @throws javax.servlet.ServletException
     */
    @Override
    public void doOptions(HttpServletRequest req, HttpServletResponse res)
            throws IOException, ServletException {
        doPost(req, res);
    }

    /**
     * Delegate the request processing to an instance of {@link CometSupport}
     *
     * @param req the {@link HttpServletRequest}
     * @param res the {@link HttpServletResponse}
     * @throws java.io.IOException
     * @throws javax.servlet.ServletException
     */
    @Override
    public void doTrace(HttpServletRequest req, HttpServletResponse res)
            throws IOException, ServletException {
        doPost(req, res);
    }

    /**
     * Delegate the request processing to an instance of {@link CometSupport}
     *
     * @param req the {@link HttpServletRequest}
     * @param res the {@link HttpServletResponse}
     * @throws java.io.IOException
     * @throws javax.servlet.ServletException
     */
    @Override
    public void doDelete(HttpServletRequest req, HttpServletResponse res)
            throws IOException, ServletException {
        doPost(req, res);
    }

    /**
     * Delegate the request processing to an instance of {@link CometSupport}
     *
     * @param req the {@link HttpServletRequest}
     * @param res the {@link HttpServletResponse}
     * @throws java.io.IOException
     * @throws javax.servlet.ServletException
     */
    @Override
    public void doPut(HttpServletRequest req, HttpServletResponse res)
            throws IOException, ServletException {
        doPost(req, res);
    }

    /**
     * Delegate the request processing to an instance of {@link CometSupport}
     *
     * @param req the {@link HttpServletRequest}
     * @param res the {@link HttpServletResponse}
     * @throws java.io.IOException
     * @throws javax.servlet.ServletException
     */
    @Override
    public void doGet(HttpServletRequest req, HttpServletResponse res)
            throws IOException, ServletException {
        doPost(req, res);
    }

    /**
     * Delegate the request processing to an instance of {@link CometSupport}
     *
     * @param req the {@link HttpServletRequest}
     * @param res the {@link HttpServletResponse}
     * @throws java.io.IOException
     * @throws javax.servlet.ServletException
     */
    @Override
    public void doPost(HttpServletRequest req, HttpServletResponse res)
            throws IOException, ServletException {
        doCometSupport(req, res);
    }

    /**
     * Invoke the proprietary {@link CometSupport}
     *
     * @param req
     * @param res
     * @return an {@link Action}
     * @throws IOException
     * @throws ServletException
     */
    protected Action doCometSupport(HttpServletRequest req, HttpServletResponse res)
            throws IOException, ServletException {
        req.setAttribute(BROADCASTER_FACTORY, broadcasterFactory);
        req.setAttribute(PROPERTY_USE_STREAM, useStreamForFlushingComments);

        try {
            return cometSupport.service(req, res);
        } catch (IllegalStateException ex) {
            logger.warn(ex.getMessage(),ex);
            if (ex.getMessage() != null && ex.getMessage().startsWith("Tomcat failed")) {
                if (!isFilter) {
                    logger.warn("failed using comet support: {}, error: {}", cometSupport.getClass().getName(),
                            ex.getMessage());
                    logger.warn("Using BlockingIOCometSupport.");
                }

                cometSupport = new BlockingIOCometSupport(config);
                service(req, res);
            } else {
                logger.error("AtmosphereServlet exception", ex);
                throw ex;
            }
        }
        return null;
    }

    /**
     * Hack to support Tomcat AIO like other WebServer. This method is invoked
     * by Tomcat when it detect a {@link Servlet} implements the interface
     * {@link CometProcessor} without invoking {@link Servlet#service}
     *
     * @param cometEvent the {@link CometEvent}
     * @throws java.io.IOException
     * @throws javax.servlet.ServletException
     */
    public void event(CometEvent cometEvent) throws IOException, ServletException {
        HttpServletRequest req = cometEvent.getHttpServletRequest();
        HttpServletResponse res = cometEvent.getHttpServletResponse();
        req.setAttribute(TomcatCometSupport.COMET_EVENT, cometEvent);

        if (!isCometSupportSpecified && !isCometSupportConfigured.getAndSet(true)) {
            synchronized (cometSupport) {
                if (!cometSupport.getClass().equals(TomcatCometSupport.class)) {
                    logger.warn("TomcatCometSupport is enabled, switching to it");
                    cometSupport = new TomcatCometSupport(config);
                }
            }
        }

        doCometSupport(req, res);
    }

    /**
     * Hack to support JBossWeb AIO like other WebServer. This method is invoked
     * by Tomcat when it detect a {@link Servlet} implements the interface
     * {@link HttpEventServlet} without invoking {@link Servlet#service}
     *
     * @param httpEvent the {@link CometEvent}
     * @throws java.io.IOException
     * @throws javax.servlet.ServletException
     */
    public void event(HttpEvent httpEvent) throws IOException, ServletException {
        HttpServletRequest req = httpEvent.getHttpServletRequest();
        HttpServletResponse res = httpEvent.getHttpServletResponse();
        req.setAttribute(JBossWebCometSupport.HTTP_EVENT, httpEvent);

        if (!isCometSupportSpecified && !isCometSupportConfigured.getAndSet(true)) {
            synchronized (cometSupport) {
                if (!cometSupport.getClass().equals(JBossWebCometSupport.class)) {
                    logger.warn("JBossWebCometSupport is enabled, switching to it");
                    cometSupport = new JBossWebCometSupport(config);
                }
            }
        }
        doCometSupport(req, res);
    }

    /**
     * Weblogic specific comet based implementation.
     *
     * @param rrk
     * @return true if suspended
     * @throws java.io.IOException
     * @throws javax.servlet.ServletException
     */
    protected boolean doRequest(RequestResponseKey rrk) throws IOException, ServletException {
        try {
            rrk.getRequest().getSession().setAttribute(WebLogicCometSupport.RRK, rrk);
            Action action = doCometSupport(rrk.getRequest(), rrk.getResponse());
            if (action.type == Action.TYPE.SUSPEND) {
                if (action.timeout == -1) {
                    rrk.setTimeout(Integer.MAX_VALUE);
                } else {
                    rrk.setTimeout((int) action.timeout);
                }
            }
            return action.type == Action.TYPE.SUSPEND;
        } catch (IllegalStateException ex) {
            logger.error("AtmosphereServlet.doRequest exception", ex);
            throw ex;
        }
    }

    /**
     * Weblogic specific comet based implementation.
     *
     * @param rrk
     * @throws java.io.IOException
     * @throws javax.servlet.ServletException
     */
    protected void doResponse(RequestResponseKey rrk, Object context)
            throws IOException, ServletException {
        rrk.getResponse().flushBuffer();
    }

    /**
     * Weblogic specific comet based implementation.
     *
     * @param rrk
     * @throws java.io.IOException
     * @throws javax.servlet.ServletException
     */
    protected void doTimeout(RequestResponseKey rrk) throws IOException, ServletException {
        ((AsynchronousProcessor) cometSupport).timedout(rrk.getRequest(), rrk.getResponse());
    }

    /**
     * Return the default {@link Broadcaster} class name.
     *
     * @return the broadcasterClassName
     */
    public static String getDefaultBroadcasterClassName() {
        return broadcasterClassName;
    }

    /**
     * Set the default {@link Broadcaster} class name
     *
     * @param bccn the broadcasterClassName to set
     */
    public static void setDefaultBroadcasterClassName(String bccn) {
        broadcasterClassName = bccn;
    }

    /**
     * <tt>true</tt> if Atmosphere uses {@link HttpServletResponse#getOutputStream()}
     * by default for write operation.
     *
     * @return the useStreamForFlushingComments
     */
    public boolean isUseStreamForFlushingComments() {
        return useStreamForFlushingComments;
    }

    /**
     * Set to <tt>true</tt> so Atmosphere uses {@link HttpServletResponse#getOutputStream()}
     * by default for write operation. Default is false.
     *
     * @param useStreamForFlushingComments the useStreamForFlushingComments to set
     */
    public void setUseStreamForFlushingComments(boolean useStreamForFlushingComments) {
        this.useStreamForFlushingComments = useStreamForFlushingComments;
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
    public AtmosphereServlet setBroadcasterFactory(final BroadcasterFactory broadcasterFactory) throws IllegalAccessException, InstantiationException, ClassNotFoundException {
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
    public void setBroadcasterCacheClassName(String broadcasterCacheClassName) throws IllegalAccessException, InstantiationException, ClassNotFoundException {
        this.broadcasterCacheClassName = broadcasterCacheClassName;
        configureBroadcaster();
    }

    /**
     * Add a new Broadcaster class name AtmosphereServlet can use when initializing requests, and when
     * atmosphere.xml broadcaster element is unspecified.
     *
     * @param broadcasterTypeString
     */
    public void addBroadcasterType(String broadcasterTypeString) {
        broadcasterTypes.add(broadcasterTypeString);
    }

    /**
     * See {@link org.atmosphere.ping.AtmospherePing}
     */
    protected void pingForStats() {
        ATMOSPHERE_PING_SUPPORT.invoke();
    }

    /**
     * https://issues.apache.org/jira/browse/WICKET-3190
     */
    private static class JettyRequestFix extends HttpServletRequestWrapper {

        public JettyRequestFix(HttpServletRequest request) {
            super(request);
        }

        /**
         * Jetty's Websocket doesn't computer the ContextPath properly for WebSocket.
         * @return
         */
        public String getContextPath() {
            String uri = getRequestURI();
            String path = super.getContextPath();
            if (path == null) {
                path = uri.substring(0, uri.indexOf("/", 1));
            }
            return path;
        }
    }

    /**
     * Jetty 7 and up WebSocket support.
     *
     * @param request
     * @param protocol
     * @return a {@link WebSocket}}
     */
    @Override
    protected WebSocket doWebSocketConnect(final HttpServletRequest request, final String protocol) {
        logger.info("WebSocket upgrade requested");

        return new WebSocket() {
            private WebSocketProcessor webSocketProcessor;

            @Override
            public void onConnect(WebSocket.Outbound outbound) {
                webSocketProcessor = new WebSocketProcessor(AtmosphereServlet.this, new JettyWebSocketSupport(outbound));
                try {
                    webSocketProcessor.connect(new JettyRequestFix(request));
                } catch (IOException e) {
                    logger.warn("failed to connect to web socket", e);
                }
            }

            @Override
            public void onMessage(byte frame, String data) {
                webSocketProcessor.broadcast(frame, data);
            }

            @Override
            public void onMessage(byte frame, byte[] data, int offset, int length) {
                webSocketProcessor.broadcast(frame, new String(data, offset, length));
            }

            @Override
            public void onFragment(boolean more, byte opcode, byte[] data, int offset, int length) {
                webSocketProcessor.broadcast(opcode, new String(data, offset, length));
            }

            @Override
            public void onDisconnect() {
                webSocketProcessor.close();
            }
        };
    }

    private static class AtmospherePingSupport {

        private final Method method;
        private final String[] version;

        private AtmospherePingSupport() {
            Method method = null;
            String[] version = null;
            try {
                Class ping = Class.forName("org.atmosphere.ping.AtmospherePing");
                method = ping.getMethod("ping", new Class[]{String.class});
                version = new String[]{Version.getRawVersion()};
            }
            catch (Exception e) {
            }

            this.method = method;
            this.version = (version == null ? new String[]{"no version found"} : version);

            invoke();
        }

        private void invoke() {
            if (method == null) {
                return;
            }

            try {
                method.invoke(null, version);
            }
            catch (Exception ignore) {
            }
        }
    }
}
