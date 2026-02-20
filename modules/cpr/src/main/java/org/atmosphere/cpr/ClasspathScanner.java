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

import org.atmosphere.annotation.Processor;
import org.atmosphere.util.IOUtils;
import org.atmosphere.websocket.WebSocketProtocol;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

import static org.atmosphere.cpr.AtmosphereFramework.DEFAULT_HANDLER_PATH;
import static org.atmosphere.cpr.AtmosphereFramework.DEFAULT_LIB_PATH;
import static org.atmosphere.util.IOUtils.realPath;

/**
 * Encapsulates classpath scanning configuration and state for the Atmosphere framework.
 * Manages annotation processing, package scanning, and handler/WebSocket auto-detection.
 */
public class ClasspathScanner {

    private static final Logger logger = LoggerFactory.getLogger(ClasspathScanner.class);

    String annotationProcessorClassName = "org.atmosphere.cpr.DefaultAnnotationProcessor";
    AnnotationProcessor annotationProcessor;
    boolean annotationFound;
    boolean scanDone;
    final List<String> packages = new ArrayList<>();
    final LinkedList<String> annotationPackages = new LinkedList<>();
    final ArrayList<String> possibleComponentsCandidate = new ArrayList<>();
    boolean allowAllClassesScan = true;
    String handlersPath = DEFAULT_HANDLER_PATH;
    String libPath = DEFAULT_LIB_PATH;

    private final AtmosphereConfig config;

    ClasspathScanner(AtmosphereConfig config) {
        this.config = config;
    }

    /**
     * Reset all state. Called during framework destroy/reset.
     */
    void clear() {
        possibleComponentsCandidate.clear();
        packages.clear();
        annotationPackages.clear();
        annotationFound = false;
        scanDone = false;
    }

    /**
     * Prevent Atmosphere from scanning the entire class path.
     */
    void preventOOM() {
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

    void configureScanningPackage(ServletConfig sc, String value) {
        String packageName = sc.getInitParameter(value);
        if (packageName != null) {
            String[] list = packageName.split(",");
            Collections.addAll(packages, list);
        }
    }

    void defaultPackagesToScan() {
        // Atmosphere HA/Pro
        packages.add("io.async.control");
        packages.add("io.async.satellite");
        packages.add("io.async.postman");
    }

    void configureAnnotationPackages() {
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

    void installAnnotationProcessor(ServletConfig sc) {
        String s = sc.getInitParameter(ApplicationConfig.ANNOTATION_PROCESSOR);
        if (s != null) {
            annotationProcessorClassName = s;
        }
    }

    void getFiles(File f) {
        if (scanDone) return;

        File[] files = f.listFiles();
        if (files != null) {
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
    }

    @SuppressWarnings("unchecked")
    void autoConfigureService(ServletContext sc) throws IOException {
        var fwk = config.framework();
        String path = handlersPath != DEFAULT_HANDLER_PATH ? handlersPath : realPath(sc, handlersPath);
        try {
            annotationProcessor = fwk.newClassInstance(AnnotationProcessor.class,
                    (Class<AnnotationProcessor>) IOUtils.loadClass(fwk.getClass(), annotationProcessorClassName));
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
                String pathLibs = !libPath.equals(DEFAULT_LIB_PATH) ? libPath : realPath(sc, DEFAULT_LIB_PATH);
                if (pathLibs != null) {
                    var libFolder = new File(pathLibs);
                    File[] jars = libFolder.listFiles((arg0, arg1) -> arg1.endsWith(".jar"));

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
        } finally {
            if (annotationProcessor != null) {
                annotationProcessor.destroy();
            }
        }
    }

    void autoDetectAtmosphereHandlers(ServletContext servletContext, ClassLoader classloader)
            throws MalformedURLException {
        var fwk = config.framework();

        // If Handler has been added
        if (!fwk.getHandlerRegistry().handlers().isEmpty()) return;

        logger.info("Auto detecting atmosphere handlers {}", handlersPath);

        String rp = servletContext.getRealPath(handlersPath);

        // Weblogic bug
        if (rp == null) {
            URL u = servletContext.getResource(handlersPath);
            if (u == null) return;
            rp = u.getPath();
        }

        loadAtmosphereHandlersFromPath(classloader, rp);
    }

    @SuppressWarnings("unchecked")
    void loadAtmosphereHandlersFromPath(ClassLoader classloader, String realPath) {
        var fwk = config.framework();
        var file = new File(realPath);

        if (file.exists() && file.isDirectory()) {
            getFiles(file);
            scanDone = true;

            for (String className : possibleComponentsCandidate) {
                try {
                    className = className.replace('\\', '/');
                    className = className.replaceFirst("^.*/(WEB-INF|target)(?:/scala-[^/]+)?/(test-)?classes/(.*)\\.class", "$3").replace("/", ".");
                    Class<?> clazz = classloader.loadClass(className);

                    if (AtmosphereHandler.class.isAssignableFrom(clazz)) {
                        AtmosphereHandler handler = fwk.newClassInstance(AtmosphereHandler.class, (Class<AtmosphereHandler>) clazz);
                        String path = "/" + handler.getClass().getSimpleName();
                        var handlerRegistry = fwk.getHandlerRegistry();
                        handlerRegistry.handlers().put(handlerRegistry.normalizePath(path),
                                new AtmosphereHandlerWrapper(fwk.getBroadcasterFactory(), handler, path, config));
                        logger.info("Installed AtmosphereHandler {} mapped to context-path: {}", handler, handler.getClass().getName());
                    }
                } catch (Throwable t) {
                    logger.trace("failed to load class as an AtmosphereHandler: " + className, t);
                }
            }
        }
    }

    void autoDetectWebSocketHandler(ServletContext servletContext, ClassLoader classloader)
            throws MalformedURLException {
        var fwk = config.framework();

        if (fwk.getWebSocketConfig().hasNewProtocol()) return;

        logger.info("Auto detecting WebSocketHandler in {}", handlersPath);
        loadWebSocketFromPath(classloader, realPath(servletContext, handlersPath));
    }

    void loadWebSocketFromPath(ClassLoader classloader, String realPath) {
        if (realPath == null || realPath.isEmpty()) return;
        var fwk = config.framework();

        var file = new File(realPath);

        if (file.exists() && file.isDirectory()) {
            getFiles(file);
            scanDone = true;

            for (String className : possibleComponentsCandidate) {
                try {
                    className = className.replace('\\', '/');
                    className = className.replaceFirst("^.*/(WEB-INF|target)(?:/scala-[^/]+)?/(test-)?classes/(.*)\\.class", "$3").replace("/", ".");
                    Class<?> clazz = classloader.loadClass(className);

                    if (WebSocketProtocol.class.isAssignableFrom(clazz)) {
                        fwk.getWebSocketConfig().setProtocolClassName(clazz.getName());
                        logger.info("Auto-detected WebSocketProtocol {}", fwk.getWebSocketConfig().getProtocolClassName());
                    }
                } catch (Throwable t) {
                    logger.trace("failed to load class as an WebSocketProtocol: " + className, t);
                }
            }
        }
    }

    void loadConfiguration(ServletConfig sc) throws Exception {
        var fwk = config.framework();

        if (!fwk.isAutoDetectHandlers()) return;

        URL url = sc.getServletContext().getResource(handlersPath);
        ClassLoader urlC = url == null ? fwk.getClass().getClassLoader() : new URLClassLoader(new URL[]{url},
                Thread.currentThread().getContextClassLoader());
        fwk.loadAtmosphereDotXml(sc.getServletContext().
                getResourceAsStream(fwk.getAtmosphereDotXmlPath()), urlC);

        if (fwk.getHandlerRegistry().handlers().isEmpty()) {
            autoDetectAtmosphereHandlers(sc.getServletContext(), urlC);

            if (fwk.getHandlerRegistry().handlers().isEmpty()) {
                fwk.detectSupportedFramework(sc);
            }
        }

        autoDetectWebSocketHandler(sc.getServletContext(), urlC);
    }
}
