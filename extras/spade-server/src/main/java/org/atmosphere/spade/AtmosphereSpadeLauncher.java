/*
 * Copyright 2011 Jeanfrancois Arcand
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

package org.atmosphere.spade;

import com.sun.grizzly.http.SelectorThread;
import com.sun.grizzly.http.servlet.deployer.WebAppAdapter;
import com.sun.grizzly.standalone.comet.Comet;
import com.sun.grizzly.tcp.Adapter;
import org.atmosphere.grizzly.AtmosphereAdapter;

/**
 * Simple REST based WebServer class that configure Atmosphere, Jersey on top og Grizzly.
 *
 * @author Jean-Francois Arcand
 */
public final class AtmosphereSpadeLauncher extends Comet {

    private String resourcesPackage = null;

    private String servletPath = "";

    private AtmosphereSpadeLauncher() {
    }

    public static void main(String args[]) throws Exception {
        AtmosphereSpadeLauncher sl = new AtmosphereSpadeLauncher();
        sl.start(args);
    }

    @Override
    public void printHelpAndExit() {
        System.err.println("Usage: " + AtmosphereSpadeLauncher.class.getCanonicalName() + " [options]");
        System.err.println();
        System.err.println("    -p, --port=port                   Runs AtmosphereSpadeServer on the specified port.");
        System.err.println("                                      Default: 8080");
        System.err.println("    -a, --apps=application path       The AtmosphereServlet folder or jar or war location.");
        System.err.println("                                      Default: .");
        System.err.println("    -rp, --respourcespackage=package  The resources package name");
        System.err.println("                                      Default: ");
        System.err.println("    -sp, --servletPath=path           The path AtmosphereServlet will serve resources");
        System.err.println("                                      Default: .");
        System.err.println("    -h, --help                        Show this help message.");
        System.exit(1);
    }

    @Override
    public boolean parseOptions(String[] args) {
        // parse options
        for (int i = 0; i < args.length - 1; i++) {
            String arg = args[i];

            if ("-h".equals(arg) || "--help".equals(arg)) {
                printHelpAndExit();
            } else if ("-a".equals(arg)) {
                i++;
                applicationLoc = args[i];
            } else if (arg.startsWith("--application=")) {
                applicationLoc = arg.substring("--application=".length(), arg.length());
            } else if ("-p".equals(arg)) {
                i++;
                setPort(args[i]);
            } else if (arg.startsWith("--port=")) {
                String num = arg.substring("--port=".length(), arg.length());
                setPort(num);
            } else if ("-rp".equals(arg)) {
                i++;
                resourcesPackage = args[i];
            } else if (arg.startsWith("--resourcespackage=")) {
                String val = arg.substring("--resourcespackage=".length(), arg.length());
                resourcesPackage = val;
            } else if ("-sp".equals(arg)) {
                i++;
                servletPath = args[i];
            } else if (arg.startsWith("--servletpath=")) {
                String val = arg.substring("--servletpath=".length(), arg.length());
                servletPath = val;
            }
        }

        if (applicationLoc == null) {
            System.err.println("Illegal War|Jar file or folder location.");
            printHelpAndExit();
        }

        return true;
    }


    @Override
    public Adapter configureAdapter(SelectorThread st) {
        st.setDisplayConfiguration(true);
        AtmosphereAdapter adapter = new AtmosphereAdapter();
        adapter.addRootFolder(st.getWebAppRootPath());
        adapter.setHandleStaticResources(true);

        // be sure to convert Windows path
        applicationLoc = applicationLoc.replaceAll("\\\\", "/");

        String warName = "";

        if (applicationLoc.lastIndexOf("/") > -1) {
            warName = applicationLoc.substring(applicationLoc.lastIndexOf("/"), applicationLoc.lastIndexOf("."));
        } else {
            warName = applicationLoc;
        }

        adapter.setContextPath(warName);

        if (!servletPath.startsWith("/")) {
            servletPath = "/" + servletPath;
        }
        adapter.setServletPath(WebAppAdapter.getServletPath(servletPath));

        adapter.setResourcePackage(resourcesPackage);
        return adapter;
    }

}
