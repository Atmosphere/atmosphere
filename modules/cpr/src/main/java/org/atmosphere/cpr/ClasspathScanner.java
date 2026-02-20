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
import jakarta.servlet.ServletConfig;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

import static org.atmosphere.cpr.AtmosphereFramework.DEFAULT_HANDLER_PATH;
import static org.atmosphere.cpr.AtmosphereFramework.DEFAULT_LIB_PATH;

/**
 * Encapsulates classpath scanning configuration and state for the Atmosphere framework.
 * Manages annotation processing, package scanning, and handler/WebSocket auto-detection.
 */
public class ClasspathScanner {

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
}
