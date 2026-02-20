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

import java.util.ArrayList;
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
}
