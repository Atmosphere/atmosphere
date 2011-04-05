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
/**
 *
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER. *
 * Copyright 2007-2008 Sun Microsystems, Inc. All rights reserved. *
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

import com.sun.grizzly.http.servlet.ServletAdapter;
import com.sun.grizzly.http.servlet.deployer.WarDeployer;
import com.sun.grizzly.http.servlet.deployer.WarDeploymentConfiguration;
import com.sun.grizzly.http.servlet.deployer.WebAppAdapter;
import org.atmosphere.cpr.AtmosphereHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileFilter;
import java.net.JarURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class AtmosphereWarDeployer extends WarDeployer {

    private static final Logger logger = LoggerFactory.getLogger(AtmosphereWarDeployer.class);

    @SuppressWarnings("unchecked")
    private List<AtmosphereHandler> atmosphereHandlerList;

    @Override
    protected WebAppAdapter getWebAppAdapter(ClassLoader webAppCL) {

        // find AtmosphereHandlers
        try {
            atmosphereHandlerList = findAtmosphereHandlers((URLClassLoader) webAppCL);
        }
        catch (Throwable t) {
            logger.warn("Error finding AtmosphereHandlers", t);
        }

        return new AtmosphereWebAppAdapter();
    }

    @SuppressWarnings("unchecked")
    @Override
    protected void setExtraConfig(ServletAdapter sa, final WarDeploymentConfiguration configuration) {

        AtmosphereAdapter adapter = (AtmosphereAdapter) sa;

        // set the AtmosphereHandler presents
        if (atmosphereHandlerList != null && !atmosphereHandlerList.isEmpty()) {
            // possiblement un probleme si nous avons plusieurs handlers ?
            for (AtmosphereHandler handler : atmosphereHandlerList) {
                adapter.addAtmosphereHandler(adapter.getServletPath(), handler);
            }
        }

    }

    @SuppressWarnings("unchecked")
    public List<AtmosphereHandler> findAtmosphereHandlers(URLClassLoader classLoader) throws Exception {

        if (classLoader == null) {
            return null;
        }

        List<AtmosphereHandler> atmosphereHandlerList = new LinkedList<AtmosphereHandler>();

        URL urls[] = classLoader.getURLs();

        List<String> list = new ArrayList<String>();

        for (URL url : urls) {

            if ("file".equals(url.getProtocol())) {

                if (url.getPath().endsWith("WEB-INF/lib/") || url.getPath().endsWith("WEB-INF/classes/")) {

                    File file = null;
                    try {
                        file = new File(url.toURI());
                    } catch (Exception e) {
                        URI uri = new URI(url.toString());
                        if (uri.getAuthority() == null)
                            file = new File(uri);
                        else
                            file = new File("//" + uri.getAuthority() + url.getFile());

                    }

                    if (file != null && file.isDirectory()) {
                        list.addAll(listFilesAndFolders(file.getCanonicalPath(), 0));
                    }
                }

            } else if ("jar".equals(url.getProtocol())) {

                JarURLConnection conn = (JarURLConnection) url.openConnection();

                JarFile jar = conn.getJarFile();

                Enumeration<JarEntry> en = jar.entries();

                while (en.hasMoreElements()) {
                    JarEntry jarEntry = en.nextElement();

                    String classname = jarEntry.getName();

                    if (classname.endsWith(".class")) {
                        // have a weird case
                        // WEB-INF/classes/org/atmosphere/samples/chat/ChatAtmosphereHandler.class
                        classname = classname.replace("WEB-INF/classes/", "").replace('\\', '/').replace('/', '.').replace('$', '.');
                        list.add(classname);
                    }
                }

            }

        }

        logger.debug("Number of classes to check for AtmosphereHandler: {}", list.size());

        for (String classname : list) {
            try {
                Class<?> clazz = classLoader.loadClass(classname.substring(0, classname.indexOf(".class")));
                if (AtmosphereHandler.class.isAssignableFrom(clazz)) {
                    Object obj = clazz.newInstance();
                    if (obj instanceof AtmosphereHandler) {
                        if (!classname.startsWith("org.atmosphere.websocket")
                                && !classname.startsWith("org.atmosphere.cpr")
                                && !classname.startsWith("org.atmosphere.handler")) {
                            atmosphereHandlerList.add((AtmosphereHandler) obj);
                        }
                    }
                }
            } catch (Throwable t) {
            }
        }

        return atmosphereHandlerList;

    }

    public List<String> listFilesAndFolders(String folder, int tabCounter) {
        List<String> list = new ArrayList<String>();

        File file = new File(folder);

        if (!file.exists() || !file.isDirectory()) {
            logger.info("Parameter is not a directory: {}", folder);
            return list;
        }

        File[] fileArray = file.listFiles(new FileFilter() {

            public boolean accept(File pathname) {
                if (pathname.isDirectory() || pathname.getName().endsWith(".class")) {
                    return true;
                }
                return false;
            }
        });

        for (int i = 0; i < fileArray.length; i++) {

            if (fileArray[i].isDirectory()) {
                list.addAll(listFilesAndFolders(fileArray[i].getAbsolutePath(), tabCounter + 1));

            } else {
                String toSkip = "WEB-INF" + File.separator + "classes" + File.separator;

                String path = fileArray[i].getPath();
                path = path.substring(path.indexOf(toSkip) + toSkip.length());

                String classname = path.replace('\\', '/').replace('/', '.');

                list.add(classname);

            }
        }
        tabCounter--;

        return list;
    }
}
