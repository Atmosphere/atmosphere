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

import org.atmosphere.container.BlockingIOCometSupport;
import org.atmosphere.container.GlassFishWebSocketSupport;
import org.atmosphere.container.GlassFishv2CometSupport;
import org.atmosphere.container.GrizzlyCometSupport;
import org.atmosphere.container.JBossWebCometSupport;
import org.atmosphere.container.Jetty7CometSupport;
import org.atmosphere.container.JettyWebSocketSupport;
import org.atmosphere.container.JettyCometSupport;
import org.atmosphere.container.Servlet30Support;
import org.atmosphere.container.Tomcat7CometSupport;
import org.atmosphere.container.TomcatCometSupport;
import org.atmosphere.container.WebLogicCometSupport;
import org.atmosphere.cpr.AtmosphereServlet.AtmosphereConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedList;
import java.util.List;

/**
 * This is the default implementation of @link {CometSupportResolver}
 *
 * @author Viktor Klang
 */
public class DefaultCometSupportResolver implements CometSupportResolver {

    private static final Logger logger = LoggerFactory.getLogger(DefaultCometSupportResolver.class);

    public final static String SERVLET_30 = "javax.servlet.AsyncListener";
    public final static String GLASSFISH_V2 = "com.sun.enterprise.web.PEWebContainer";
    public final static String TOMCAT_7 = "org.apache.catalina.core.StuckThreadDetectionValve";
    public final static String TOMCAT = "org.apache.coyote.http11.Http11NioProcessor";
    public final static String JBOSS_5 = "org.jboss.";
    public final static String JETTY = "org.mortbay.util.ajax.Continuation";
    public final static String JETTY_7 = "org.eclipse.jetty.servlet.ServletContextHandler";
    public final static String JETTY_8 = "org.eclipse.jetty.continuation.Servlet3Continuation";
    public final static String GRIZZLY = "com.sun.grizzly.http.servlet.ServletAdapter";
    public final static String WEBLOGIC = "weblogic.servlet.http.FutureResponseModel";
    public final static String JBOSSWEB = "org.apache.catalina.connector.HttpEventImpl";
    public final static String GRIZZLY_WEBSOCKET = "com.sun.grizzly.websockets.WebSocketEngine";

    private final AtmosphereConfig config;

    public DefaultCometSupportResolver(final AtmosphereConfig config) {
        this.config = config;
    }

    /**
     * Convenience method that tests if a class with the given FQN is present on the classpath
     *
     * @param testClass
     * @return true if the class is present
     */
    protected boolean testClassExists(final String testClass) {
        try {
            return testClass != null && testClass.length() > 0 &&
                    Thread.currentThread().getContextClassLoader().loadClass(testClass) != null;
        }
        catch (ClassNotFoundException ex) {
            return false;
        }
        catch (NoClassDefFoundError ex) {
            return false;
        }
    }

    /**
     * Returns a list of comet support by containers available on the classpath
     *
     * @return
     */
    public List<Class<? extends CometSupport>> detectContainersPresent() {
        return new LinkedList<Class<? extends CometSupport>>() {
            {
                if (testClassExists(GLASSFISH_V2))
                    add(GlassFishv2CometSupport.class);

                if (testClassExists(JETTY))
                    add(JettyCometSupport.class);

                if (testClassExists(JETTY_7))
                    add(Jetty7CometSupport.class);

                if (testClassExists(JETTY_8))
                    add(Jetty7CometSupport.class);

                if (testClassExists(JBOSSWEB))
                    add(JBossWebCometSupport.class);

                if (testClassExists(TOMCAT_7))
                    add(Tomcat7CometSupport.class);

                if (testClassExists(TOMCAT) || testClassExists(JBOSS_5))
                    add(TomcatCometSupport.class);

                if (testClassExists(GRIZZLY))
                    add(GrizzlyCometSupport.class);

                if (testClassExists(WEBLOGIC))
                    add(WebLogicCometSupport.class);
            }
        };
    }

    public List<Class<? extends CometSupport>> detectWebSocketPresent() {
        List l =  new LinkedList<Class<? extends CometSupport>>() {
            {
                if (testClassExists(JETTY_8))
                    add(JettyWebSocketSupport.class);

                if (testClassExists(GRIZZLY_WEBSOCKET))
                    add(GlassFishWebSocketSupport.class);

            }
        };

        if (l.isEmpty()) {
            return detectContainersPresent();
        }
        return l;
    }

    /**
     * This method is used to determine the default CometSupport if all else fails
     *
     * @param preferBlocking
     * @return
     */
    public CometSupport defaultCometSupport(final boolean preferBlocking) {
        if (!preferBlocking && testClassExists(SERVLET_30)) {
            return new Servlet30Support(config);
        } else {
            return new BlockingIOCometSupport(config);
        }
    }

    /**
     * Given a Class of something that extends CometSupport, it tries to return an instance of that class
     * <p/>
     * The class has to have a visible constructor with the signature (@link {AtmosphereConfig})
     *
     * @param targetClass
     * @return an instance of the specified class
     */
    public CometSupport newCometSupport(final Class<? extends CometSupport> targetClass) {
        try {
            return targetClass.getDeclaredConstructor(new Class[]{AtmosphereConfig.class})
                    .newInstance(config);
        }
        catch (final Exception e) {
            logger.error("failed to create comet support class: {}, error: {}", targetClass, e.getMessage());
            throw new IllegalArgumentException(
                    "Comet support class " + targetClass.getCanonicalName() + " has bad signature.", e);
        }
    }

    public CometSupport newCometSupport(final String targetClassFQN) {
        try {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            return (CometSupport) cl.loadClass(targetClassFQN)
                    .getDeclaredConstructor(new Class[]{AtmosphereConfig.class}).newInstance(config);
        }
        catch (final Exception e) {
            logger.error("failed to create comet support class: {}, error: {}", targetClassFQN, e.getMessage());
            throw new IllegalArgumentException("Comet support class " + targetClassFQN + " has bad signature.", e);
        }
    }

    /**
     * This method is the general interface to the outside world
     *
     * @param useNativeIfPossible - should the resolver try to use a native container comet support if present?
     * @param defaultToBlocking   - should the resolver default to blocking IO comet support?
     * @return an instance of CometSupport
     */
    public CometSupport resolve(final boolean useNativeIfPossible, final boolean defaultToBlocking) {
        final CometSupport servletAsyncSupport = defaultCometSupport(defaultToBlocking);

        final CometSupport nativeSupport;
        if (!defaultToBlocking && (useNativeIfPossible ||
                servletAsyncSupport.getClass().getName().equals(BlockingIOCometSupport.class.getName()))) {
            nativeSupport = resolveNativeCometSupport(detectContainersPresent());
            return nativeSupport == null ? servletAsyncSupport : nativeSupport;
        }
        return servletAsyncSupport;
    }

    public CometSupport resolve(boolean useNativeIfPossible, boolean defaultToBlocking, boolean useWebsocketIfPossible) {
        CometSupport cs;
        if (!useWebsocketIfPossible) {
            cs = resolve(useNativeIfPossible, defaultToBlocking);
        } else {
            cs = resolveWebSocket(detectWebSocketPresent());
        }

        if (cs == null) {
            return new BlockingIOCometSupport(config);
        } else {
            return cs;
        }
    }

    public CometSupport resolveWebSocket(final java.util.List<Class<? extends CometSupport>> available) {
        if (available == null || available.isEmpty()) return null;
        else return newCometSupport(available.get(0));
    }

    /**
     * This method is called to determine which native comet support to the used
     *
     * @param available
     * @return the result of @link {resolveMultipleNativeSupportConflict} if there are more than 1 item in the list of available ontainers
     */
    protected CometSupport resolveNativeCometSupport(final java.util.List<Class<? extends CometSupport>> available) {
        if (available == null || available.isEmpty()) return null;
        else if (available.size() == 1) return newCometSupport(available.get(0));
        else return resolveMultipleNativeSupportConflict(available);
    }

    /**
     * This method is called if there are more than one potential native container in scope
     *
     * @return a CometSupport instance
     */
    protected CometSupport resolveMultipleNativeSupportConflict(final List<Class<? extends CometSupport>> available) {
        final StringBuilder b = new StringBuilder("Found multiple containers, please specify which one to use: ");
        for (Class<? extends CometSupport> cs : available) {
            b.append((cs != null) ? cs.getCanonicalName() : "null").append(", ");
        }

        b.append(" until you do, Atmosphere will use:" + available.get(0));
        logger.warn("{}", b.toString());
        return newCometSupport(available.get(0));
    }
}
