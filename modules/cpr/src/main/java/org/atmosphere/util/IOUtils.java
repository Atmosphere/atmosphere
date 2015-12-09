/*
 * Copyright 2015 Async-IO.org
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

import org.atmosphere.config.service.DeliverTo;
import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereRequestImpl;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereServlet;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.MeteorServlet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletContext;
import javax.servlet.ServletRegistration;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Field;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    /**
     * <p>
     * Delivers the given message according to the specified {@link org.atmosphere.config.service.DeliverTo configuration).
     * </p>
     *
     * @param o              the message
     * @param deliverConfig  the annotation state
     * @param defaultDeliver the strategy applied if deliverConfig is {@code null}
     * @param r              the resource
     */
    public static void deliver(final Object o,
                               final DeliverTo deliverConfig,
                               final DeliverTo.DELIVER_TO defaultDeliver,
                               final AtmosphereResource r) {
        final DeliverTo.DELIVER_TO deliverTo = deliverConfig == null ? defaultDeliver : deliverConfig.value();
        switch (deliverTo) {
            case RESOURCE:
                r.getBroadcaster().broadcast(o, r);
                break;
            case BROADCASTER:
                r.getBroadcaster().broadcast(o);
                break;
            case ALL:
                for (Broadcaster b : r.getAtmosphereConfig().getBroadcasterFactory().lookupAll()) {
                    b.broadcast(o);
                }
                break;

        }
    }

    public static Object readEntirely(AtmosphereResource r) throws IOException {
        AtmosphereRequest request = r.getRequest();
        return isBodyBinary(request) ? readEntirelyAsByte(r) : readEntirelyAsString(r).toString();
    }

    public final static boolean isBodyBinary(AtmosphereRequest request) {
        if (request.getContentType() != null
                && request.getContentType().equalsIgnoreCase(FORCE_BINARY) || request.getHeader(X_ATMO_BINARY) != null)
            return true;
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

    public static StringBuilder readEntirelyAsString(AtmosphereResource r) throws IOException {
        final StringBuilder stringBuilder = new StringBuilder();

        boolean readGetBody = r.getAtmosphereConfig().getInitParameter(ApplicationConfig.READ_GET_BODY, false);
        if (!readGetBody && AtmosphereResourceImpl.class.cast(r).getRequest(false).getMethod().equalsIgnoreCase("GET")) {
            logger.debug("Blocking an I/O read operation from a GET request. To enable GET + body, set {} to true", ApplicationConfig.READ_GET_BODY);
            return stringBuilder;
        }

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
                    try {
                        while ((bytesRead = bufferedReader.read(charBuffer)) > 0) {
                            stringBuilder.append(charBuffer, 0, bytesRead);
                        }
                    } catch (NullPointerException ex) {
                        // https://java.net/jira/browse/GRIZZLY-1676
                    }
                } else {
                    stringBuilder.append("");
                }
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
            AtmosphereRequestImpl.Body body = request.body();
            try {
                stringBuilder.append(body.hasString() ? body.asString() : new String(body.asBytes(), body.byteOffset(), body.byteLength(), request.getCharacterEncoding()));
            } catch (UnsupportedEncodingException e) {
                logger.error("", e);
            }
        }
        return stringBuilder;
    }

    public static byte[] readEntirelyAsByte(AtmosphereResource r) throws IOException {
        AtmosphereRequest request = r.getRequest();

        boolean readGetBody = r.getAtmosphereConfig().getInitParameter(ApplicationConfig.READ_GET_BODY, false);
        if (!readGetBody && AtmosphereResourceImpl.class.cast(r).getRequest(false).getMethod().equalsIgnoreCase("GET")) {
            logger.debug("Blocking an I/O read operation from a GET request. To enable GET + body, set {} to true", ApplicationConfig.READ_GET_BODY);
            return new byte[0];
        }

        AtmosphereRequestImpl.Body body = request.body();
        if (request.body().isEmpty()) {
            BufferedInputStream bufferedStream = null;
            ByteArrayOutputStream bbIS = new ByteArrayOutputStream();
            try {
                try {
                    InputStream inputStream = request.getInputStream();
                    if (inputStream != null) {
                        bufferedStream = new BufferedInputStream(inputStream);
                    }
                } catch (IllegalStateException ex) {
                    logger.trace("", ex);
                    Reader reader = request.getReader();
                    if (reader != null) {
                        bufferedStream = new BufferedInputStream(new ReaderInputStream(reader));
                    }
                }

                if (bufferedStream != null) {
                    byte[] bytes = new byte[8192];
                    int bytesRead = 0;
                    while (bytesRead != -1) {
                        bytesRead = bufferedStream.read(bytes);
                        if (bytesRead > 0)
                            bbIS.write(bytes, 0, bytesRead);
                    }

                } else {
                    bbIS.write("".getBytes());
                }
            } finally {
                if (bufferedStream != null) {
                    try {
                        bufferedStream.close();
                    } catch (IOException ex) {
                        logger.warn("", ex);
                    }
                }
            }
            return bbIS.toByteArray();
        } else if (body.hasString()) {
            try {
                return readEntirelyAsString(r).toString().getBytes(request.getCharacterEncoding());
            } catch (UnsupportedEncodingException e) {
                logger.error("", e);
            }
        } else if (body.hasBytes()) {
            return Arrays.copyOfRange(body.asBytes(), body.byteOffset(), body.byteOffset() + body.byteLength());
        }
        throw new IllegalStateException("No body " + r);
    }

    public static String guestServletPath(AtmosphereConfig config) {
        String servletPath = "";
        if (config.getServletConfig() != null) {
            servletPath = getCleanedServletPath(guestRawServletPath(config));
        } else {
            throw new IllegalStateException("Unable to configure jsr356 at that stage");
        }
        return servletPath;
    }

    public static String guestRawServletPath(AtmosphereConfig config) {
        String servletPath = "";
        try {
            if (config.getServletConfig() != null) {
                ServletRegistration s = config.getServletContext().getServletRegistration(config.getServletConfig().getServletName());

                if (s == null) {
                    s = config.getServletContext().getServletRegistration(VoidServletConfig.ATMOSPHERE_SERVLET);
                }

                if ( s == null) {
                    for (Map.Entry<String, ? extends ServletRegistration> servlet : config.getServletContext().getServletRegistrations().entrySet()) {
                        if (knownClasses.contains(servlet.getValue().getClassName())) {
                            s = servlet.getValue();
                            break;
                        }
                    }

                    if (s == null) {
                        throw new IllegalStateException("Unable to configure jsr356 at that stage. No Servlet associated with "
                                + config.getServletConfig().getServletName());
                    }
                }

                if (s.getMappings().size() > 1) {
                    logger.warn("More than one Servlet Mapping defined. WebSocket may not work {}", s);
                }

                for (String m : s.getMappings()) {
                    servletPath = m;
                }
            } else {
                throw new IllegalStateException("Unable to configure jsr356 at that stage");
            }
            return servletPath;
        } catch (Exception ex) {
            logger.error("", ex);
            throw new IllegalStateException("Unable to configure jsr356 at that stage");
        }
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

        if (fullServletPath.equalsIgnoreCase("/*")) return "";

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

    /**
     * Loading the specified class using some heuristics to support various containers
     * The order of preferece is:
     *  1. Thread.currentThread().getContextClassLoader()
     *  2. Class.forName
     *  3. thisClass.getClassLoader()
     *
     * @param thisClass
     * @param className
     * @return
     * @throws Exception
     */
    public static Class<?> loadClass(Class<?> thisClass, String className) throws Exception {
        try {
            return Thread.currentThread().getContextClassLoader().loadClass(className);
        } catch (Throwable t) {
            try {
                return Class.forName(className);
            } catch (Exception t2) {
                if (thisClass != null) {
                    return thisClass.getClassLoader().loadClass(className);
                }
                throw t2;
            }
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

    /**
     * <p>
     * This method reads the given file stored under "META-INF/services" and accessed through the framework's class loader
     * to specify a list of {@link org.atmosphere.cpr.AtmosphereFramework.MetaServiceAction actions} to be done on different
     * service classes ({@link org.atmosphere.cpr.AtmosphereInterceptor}, {@link org.atmosphere.cpr.BroadcastFilter}, etc).
     * </p>
     * <p/>
     * <p>
     * The file content should follows the following format:
     * <pre>
     * INSTALL
     * com.mycompany.MyInterceptor
     * com.mycompany.MyFilter
     * EXCLUDE
     * org.atmosphere.interceptor.HeartbeatInterceptor
     * </pre>
     * </p>
     * <p/>
     * <p>
     * If you don't specify any {@link org.atmosphere.cpr.AtmosphereFramework.MetaServiceAction} before a class, then
     * default action will be {@link org.atmosphere.cpr.AtmosphereFramework.MetaServiceAction#INSTALL}.
     * </p>
     * <p/>
     * <p>
     * Important note: you must specify a class declared inside a package. Since creating classes in the source root is
     * a bad practice, the method does not deal with it to improve its performances.
     * </p>
     *
     * @param path the service file to read
     * @return the map associating class to action
     */
    public static Map<String, AtmosphereFramework.MetaServiceAction> readServiceFile(final String path) {
        final Map<String, AtmosphereFramework.MetaServiceAction> b = new LinkedHashMap<String, AtmosphereFramework.MetaServiceAction>();

        String line;
        InputStream is = null;
        BufferedReader reader = null;
        AtmosphereFramework.MetaServiceAction action = AtmosphereFramework.MetaServiceAction.INSTALL;

        try {
            is = AtmosphereFramework.class.getClassLoader().getResourceAsStream(path);

            if (is == null) {
                logger.trace("META-INF/services/{} not found in class loader", path);
                return b;
            }

            reader = new BufferedReader(new InputStreamReader(is));

            while (true) {
                line = reader.readLine();

                if (line == null) {
                    break;
                } else if (line.isEmpty()) {
                    continue;
                } else if (line.indexOf('.') == -1) {
                    action = AtmosphereFramework.MetaServiceAction.valueOf(line);
                } else {
                    b.put(line, action);
                }
            }
            logger.info("Successfully loaded and installed {}", path);
        } catch (IOException e) {
            logger.trace("Unable to read META-INF/services/{} from class loader", path, e);
        } finally {
            close(is, reader);
        }

        return b;
    }

    /**
     * <p>
     * Tries to close the given objects and log the {@link IOException} at INFO level
     * to make the code more readable when we assume that the {@link IOException} won't be managed.
     * </p>
     * <p/>
     * <p>
     * Also ignore {@code null} parameters.
     * </p>
     *
     * @param closeableArray the objects to close
     */
    public static void close(final Closeable... closeableArray) {
        for (Closeable closeable : closeableArray) {
            try {
                if (closeable != null) {
                    closeable.close();
                }
            } catch (IOException ioe) {
                logger.info("Can't close the object", ioe);
            }
        }
    }

    public static String realPath(ServletContext servletContext, String targetPath) throws MalformedURLException {
        String realPath = servletContext.getRealPath(targetPath);
        if (realPath == null) {
            URL u = servletContext.getResource(targetPath);
            if (u != null) {
                realPath = u.getPath();
            } else {
                return "";
            }
        }
        return realPath;
    }
}