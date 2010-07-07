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
package org.atmosphere.grizzly;

import com.sun.grizzly.SSLConfig;
import com.sun.grizzly.comet.CometAsyncFilter;
import com.sun.grizzly.http.embed.GrizzlyWebServer;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.util.LoggerUtils;

import java.io.IOException;
import java.net.URI;

/**
 * Simple Atmosphere REST based Server builder:
 * <p>
 *    AtmosphereSpadeServer.build("http://localhost:8080/","org.foo.bar").start()
 * </p>
 * which listen on localhost:8080 for resources starting with package org.foo.bar.
 *
 * @author Jean-francois Arcand
 */
public final class AtmosphereSpadeServer {

    private final GrizzlyWebServer gws;
    private final AtmosphereAdapter aa = new AtmosphereAdapter();
    private boolean defaultToJersey = true;

    private AtmosphereSpadeServer(int port, boolean secure) {
        gws = new GrizzlyWebServer(port, ".", secure);
        gws.getSelectorThread().setDisplayConfiguration(false);
        gws.addAsyncFilter(new CometAsyncFilter());
    }

    /**
     * Create a {@link AtmosphereSpadeServer} which listen based using the URI u.
     *
     * @param u The URI the server listen for requests
     * @return an instance of AtmosphereSpadeServer
     */
    public static AtmosphereSpadeServer build(String u) {
       return AtmosphereSpadeServer.build(u,"");
    }

    /**
     * Create a {@link AtmosphereSpadeServer} which listen based on the 'u' for requests for
     * resources defined under the resources package.
     *
     * @param u The URI the server listen for requests
     * @param resourcesPackage The resources package name.
     * @return an instance of AtmosphereSpadeServer
     */
    public static AtmosphereSpadeServer build(String u, String resourcesPackage) {
        return AtmosphereSpadeServer.build(u,resourcesPackage,null);
    }

    /**
     * Create a {@link AtmosphereSpadeServer} which listen based on the 'u' for requests for
     * resources defined under the resources package.
     *
     * @param u The URI the server listen for requests
     * @param sslConfig The {@link SSLConfig}
     * @return an instance of AtmosphereSpadeServer
     */
    public static AtmosphereSpadeServer build(String u, SSLConfig sslConfig) {
        return AtmosphereSpadeServer.build(u,null,sslConfig);
    }

    /**
     * Create a {@link AtmosphereSpadeServer} which listen based on the 'u' for requests for
     * resources defined under the resources package.
     *
     * @param u The URI the server listen for requests
     * @param resourcesPackage The resources package name.
     * @param sslConfig The {@link SSLConfig}
     * @return
     */
    public static AtmosphereSpadeServer build(String u, String resourcesPackage, SSLConfig sslConfig) {
        if (u == null) {
            throw new IllegalArgumentException("The URI must not be null");
        }
        
        URI uri = URI.create(u);
        final String scheme = uri.getScheme();
        if (!scheme.startsWith("http"))
            throw new IllegalArgumentException("The URI scheme, of the URI " + u +
                    ", must be equal (ignoring case) to 'http'");

        final int port = (uri.getPort() == -1) ? 80 : uri.getPort();
        String path = uri.getPath();
        if (path == null) {
            throw new IllegalArgumentException("The URI path, of the URI " + uri +
                    ", must be non-null");
        } else if (path.length() == 0) {
            throw new IllegalArgumentException("The URI path, of the URI " + uri +
                    ", must be present");
        } else if (path.charAt(0) != '/') {
            throw new IllegalArgumentException("The URI path, of the URI " + uri +
                    ". must start with a '/'");
        }

        boolean secure = false;
        if (scheme.equalsIgnoreCase("https")) {
            secure = true;
        }

        AtmosphereSpadeServer a = new AtmosphereSpadeServer(port, secure);
        if (path.length() > 1) {
            if (path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            a.aa.setContextPath(path);
        }

        if (resourcesPackage != null) {
            a.aa.setResourcePackage(resourcesPackage);
        }

        if (sslConfig != null) {
            a.setSSLConfig(sslConfig);
        }

        return a;
    }

    private void setSSLConfig(SSLConfig sslConfig) {
        gws.setSSLConfig(sslConfig);
    }

    /**
     * Add an {@link AtmosphereHandler}
     */
    public AtmosphereSpadeServer addAtmosphereHandler(String mapping,AtmosphereHandler h){
        defaultToJersey = false;
        AtmosphereAdapter a = new AtmosphereAdapter();
        a.setServletInstance(aa.getServletInstance());
        a.addAtmosphereHandler(mapping, h);
        a.setServletPath(mapping);
        a.setHandleStaticResources(true);
        gws.addGrizzlyAdapter(a,new String[]{mapping});
        return this;
    }

    /**
     * Set the resource package name.
     */
    public void setResourcePackage(String resourcePackage){
        aa.setResourcePackage(resourcePackage);
    }

    /**
     * Start the {@link AtmosphereSpadeServer}
     * @return
     * @throws IOException
     */
    public AtmosphereSpadeServer start() throws IOException {
        LoggerUtils.getLogger().info("AtmosphereSpade Server Started on port: "
                + gws.getSelectorThread().getPort());
        if (defaultToJersey){
            aa.setHandleStaticResources(true);
            gws.addGrizzlyAdapter(aa, new String[]{"*"});
        }
        gws.start();
        return this;
    }

    /**
     * Stop the {@link AtmosphereSpadeServer}
     * @return
     * @throws IOException
     */
    public AtmosphereSpadeServer stop() throws IOException {
        gws.stop();
        return this;
    }
}
