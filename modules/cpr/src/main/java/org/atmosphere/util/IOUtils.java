/*
 * Copyright 2014 Jeanfrancois Arcand
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
package org.atmosphere.util;

import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereServlet;
import org.atmosphere.cpr.MeteorServlet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.atmosphere.cpr.HeaderConfig.FORCE_BINARY;
import static org.atmosphere.cpr.HeaderConfig.X_ATMO_BINARY;

public class IOUtils {
    private final static Logger logger = LoggerFactory.getLogger(IOUtils.class);
    private final static List<String> knownClasses;
    private final static Pattern SERVLET_PATH_PATTERN = Pattern.compile("([\\/]?[\\w-[.]]+|[\\/]\\*\\*)+");

    static {
        knownClasses = new ArrayList<String>() {
            {
                add(AtmosphereServlet.class.getName());
                add(MeteorServlet.class.getName());
                add("com.vaadin.server.VaadinServlet");
                add("org.primefaces.push.PushServlet");
            }
        };
    }

    public static Object readEntirely(AtmosphereResource r) {
        AtmosphereRequest request = r.getRequest();
        return isBodyBinary(request) ? readEntirelyAsByte(r) : readEntirelyAsString(r).toString();
    }

    public final static boolean isBodyBinary(AtmosphereRequest request) {
        if (request.getContentType() != null
                && request.getContentType().equalsIgnoreCase(FORCE_BINARY) || request.getHeader(X_ATMO_BINARY) != null) return true;
        return false;
    }

    public final static boolean isBodyEmpty(Object o) {
        if (o != null && (String.class.isAssignableFrom(o.getClass()) && String.class.cast(o).isEmpty())
                || (Byte[].class.isAssignableFrom(o.getClass()) && Byte[].class.cast(o).length == 0)) {
            return true;
        } else {
            return false;
        }
    }

    public static StringBuilder readEntirelyAsString(AtmosphereResource r) {
        final StringBuilder stringBuilder = new StringBuilder();
        AtmosphereRequest request = r.getRequest();
        if (request.body().isEmpty()) {
            BufferedReader bufferedReader = null;
            try {
                try {
                    InputStream inputStream = request.getInputStream();
                    if (inputStream != null) {
                        bufferedReader = new BufferedReader(new InputStreamReader(inputStream));
                    }
                } catch (IllegalStateException ex) {
                    logger.trace("", ex);
                    Reader reader = request.getReader();
                    if (reader != null) {
                        bufferedReader = new BufferedReader(reader);
                    }
                }

                if (bufferedReader != null) {
                    char[] charBuffer = new char[8192];
                    int bytesRead = -1;
                    while ((bytesRead = bufferedReader.read(charBuffer)) > 0) {
                        stringBuilder.append(charBuffer, 0, bytesRead);
                    }
                } else {
                    stringBuilder.append("");
                }
            } catch (IOException ex) {
                logger.warn("", ex);
            } finally {
                if (bufferedReader != null) {
                    try {
                        bufferedReader.close();
                    } catch (IOException ex) {
                        logger.warn("", ex);
                    }
                }
            }
        } else {
            AtmosphereRequest.Body body = request.body();
            try {
                stringBuilder.append(body.hasString() ? body.asString() : new String(body.asBytes(), body.byteOffset(), body.byteLength(), request.getCharacterEncoding()));
            } catch (UnsupportedEncodingException e) {
                logger.error("", e);
            }
        }
        return stringBuilder;
    }

    public static byte[] readEntirelyAsByte(AtmosphereResource r) {
        AtmosphereRequest request = r.getRequest();
        AtmosphereRequest.Body body = request.body();
        if (body.hasString()) {
            try {
                return readEntirelyAsString(r).toString().getBytes(request.getCharacterEncoding());
            } catch (UnsupportedEncodingException e) {
                logger.error("", e);
            }
        } else if (body.hasBytes()) {
            return Arrays.copyOfRange(body.asBytes(), body.byteOffset(), body.byteLength());
        }
        throw new IllegalStateException("No body " + r);
    }

    public static String guestServletPath(AtmosphereConfig config) {
        String servletPath = "";
        try {
            // TODO: pick up the first one, will fail if there are two
            servletPath = config.getServletContext().getServletRegistration(config.getServletConfig().getServletName()).getMappings().iterator().next();
            servletPath = getCleanedServletPath(servletPath);
        } catch (Exception ex) {
            logger.trace("", ex);
        }
        return servletPath;
    }

    /**
     * Used to remove trailing slash and wildcard from a servlet path.<br/><br/>
     * Examples :<br/>
     * - "/foo/" becomes "/foo"<br/>
     * - "foo/bar" becomes "/foo/bar"<br/>
     *
     * @param fullServletPath : Servlet mapping
     * @return Servlet mapping without trailing slash and wildcard
     */
    public static String getCleanedServletPath(String fullServletPath) {
        Matcher matcher = SERVLET_PATH_PATTERN.matcher(fullServletPath);

        // It should not happen if the servlet path is valid
        if (!matcher.find()) return fullServletPath;

        String servletPath = matcher.group(0);
        if (!servletPath.startsWith("/")) {
            servletPath = "/" + servletPath;
        }

        return servletPath;
    }

    private static boolean scanForAtmosphereFramework(Class<?> classToScan) {
        if (classToScan == null) return false;

        logger.trace("Scanning {}", classToScan.getName());

        // Before doing the Siberian traversal, look locally
        if (knownClasses.contains(classToScan.getName())) {
            return true;
        }

        try {
            Field[] fields = classToScan.getDeclaredFields();
            for (Field f : fields) {
                f.setAccessible(true);
                if (AtmosphereFramework.class.isAssignableFrom(f.getType())) {
                    return true;
                }
            }
        } catch (Exception ex) {
            logger.trace("", ex);
        }

        // Now try with parent
        if (scanForAtmosphereFramework(classToScan.getSuperclass())) return true;
        return false;
    }

    public static Class<?> loadClass(Class thisClass, String className) throws Exception {
        try {
            return Thread.currentThread().getContextClassLoader().loadClass(className);
        } catch (Throwable t) {
            return thisClass.getClassLoader().loadClass(className);
        }
    }

    public static boolean isAtmosphere(String className) {
        Class<? extends AtmosphereServlet> clazz;
        try {
            clazz = (Class<? extends AtmosphereServlet>) Thread.currentThread().getContextClassLoader().loadClass(className);
        } catch (Throwable t) {
            try {
                clazz = (Class<? extends AtmosphereServlet>) IOUtils.class.getClassLoader().loadClass(className);
            } catch (Exception ex) {
                return false;
            }
        }
        return AtmosphereServlet.class.isAssignableFrom(clazz);
    }

}