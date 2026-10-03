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
import org.atmosphere.cpr.MetaServiceAction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletRegistration;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.atmosphere.cpr.HeaderConfig.FORCE_BINARY;
import static org.atmosphere.cpr.HeaderConfig.X_ATMO_BINARY;

/**
 * Utility class providing I/O helper methods for message delivery, request body reading,
 * servlet path resolution, class loading, and service file parsing.
 */
public class IOUtils {
    private final static Logger logger = LoggerFactory.getLogger(IOUtils.class);
    private final static List<String> knownClasses;
    private final static Pattern SERVLET_PATH_PATTERN = Pattern.compile("([/]?[\\w-[.]]+|[/]\\*\\*)+");

    static {
        knownClasses = List.of(
                AtmosphereServlet.class.getName(),
                "com.vaadin.server.VaadinServlet",
                "org.primefaces.push.PushServlet"
        );
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
            case RESOURCE -> r.getBroadcaster().broadcast(o, r);
            case BROADCASTER -> r.getBroadcaster().broadcast(o);
            case ALL -> {
                for (Broadcaster b : r.getAtmosphereConfig().getBroadcasterFactory().lookupAll()) {
                    b.broadcast(o);
                }
            }
        }
    }

    public static Object readEntirely(AtmosphereResource r) throws IOException {
        AtmosphereRequest request = r.getRequest();
        return isBodyBinary(request) ? readEntirelyAsByte(r) : readEntirelyAsString(r).toString();
    }

    public static boolean isBodyBinary(AtmosphereRequest request) {
        return request.getContentType() != null
                && request.getContentType().equalsIgnoreCase(FORCE_BINARY) || request.getHeader(X_ATMO_BINARY) != null;
    }

    public static boolean isBodyEmpty(Object o) {
        if (o instanceof String s && s.isEmpty()) return true;
        assert o != null;
        return o instanceof Byte[] bytes && bytes.length == 0;
    }

    public static StringBuilder readEntirelyAsString(AtmosphereResource r) throws IOException {
        final var stringBuilder = new StringBuilder();

        boolean readGetBody = r.getAtmosphereConfig().getInitParameter(ApplicationConfig.READ_GET_BODY, false);
        if (!readGetBody && ((AtmosphereResourceImpl) r).getRequest(false).getMethod().equalsIgnoreCase("GET")) {
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
                    int bytesRead;
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

    /**
     * Reads the request body as text of at most {@code maxBytes} bytes, decoded
     * strictly with the request's character encoding (UTF-8 when it names none).
     * A body cached on the request is checked against the same limit. A GET body
     * is read only when {@link ApplicationConfig#READ_GET_BODY} allows it, as by
     * {@link #readEntirelyAsString(AtmosphereResource)}. A body the container
     * already opened as a {@link Reader} is decoded by the container (malformed
     * bytes replaced, not reported) and measured approximately.
     *
     * @return the body, empty when there is none
     * @throws PayloadTooLargeException when the body is larger than {@code maxBytes};
     *         a declared {@code Content-Length} over it is refused before any read
     * @throws java.nio.charset.CharacterCodingException when the body is not valid
     *         in its character encoding
     * @throws UnsupportedEncodingException when the request names an unknown
     *         encoding, or one that only decodes
     */
    public static String readEntirelyAsString(AtmosphereResource r, int maxBytes) throws IOException {
        boolean readGetBody = r.getAtmosphereConfig().getInitParameter(ApplicationConfig.READ_GET_BODY, false);
        AtmosphereRequest request = r.getRequest();
        if (!readGetBody && "GET".equalsIgnoreCase(request.getMethod())) {
            logger.debug("Blocking an I/O read operation from a GET request. To enable GET + body, set {} to true", ApplicationConfig.READ_GET_BODY);
            return "";
        }
        Charset charset = requestCharset(request);
        AtmosphereRequestImpl.Body body = request.body();
        if (!body.isEmpty()) {
            if (body.hasString()) {
                String cached = body.asString();
                if (cached.getBytes(charset).length > maxBytes) {
                    throw new PayloadTooLargeException(maxBytes);
                }
                return cached;
            }
            if (body.byteLength() > maxBytes) {
                throw new PayloadTooLargeException(maxBytes);
            }
            return decode(body.asBytes(), body.byteOffset(), body.byteLength(), charset);
        }
        if (request.getContentLength() > maxBytes) {
            throw new PayloadTooLargeException(maxBytes);
        }
        InputStream in;
        try {
            in = request.getInputStream();
        } catch (IllegalStateException ex) {
            // The body was opened as a Reader: read characters, bounded by the
            // bytes they encode to.
            logger.trace("", ex);
            return readBounded(request.getReader(), maxBytes, charset);
        }
        if (in == null) {
            return "";
        }
        var out = new ByteArrayOutputStream();
        var buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) > 0) {
            if ((long) out.size() + read > maxBytes) {
                throw new PayloadTooLargeException(maxBytes);
            }
            out.write(buffer, 0, read);
        }
        return decode(out.toByteArray(), 0, out.size(), charset);
    }

    /**
     * Reads a body the container already decoded into a {@link Reader}: its
     * malformed bytes were replaced by the container, not reported, and its size
     * is measured by encoding each chunk again, so it is approximate (a byte-order
     * mark or a surrogate pair split across chunks moves it by a few bytes).
     */
    private static String readBounded(Reader reader, int maxBytes, Charset charset) throws IOException {
        if (reader == null) {
            return "";
        }
        var text = new StringBuilder();
        var buffer = new char[8192];
        long bytes = 0;
        int read;
        while ((read = reader.read(buffer)) > 0) {
            bytes += new String(buffer, 0, read).getBytes(charset).length;
            if (bytes > maxBytes) {
                throw new PayloadTooLargeException(maxBytes);
            }
            text.append(buffer, 0, read);
        }
        return text.toString();
    }

    /** The request's character encoding, UTF-8 when it names none; an unknown one is malformed input. */
    private static Charset requestCharset(AtmosphereRequest request) throws UnsupportedEncodingException {
        String encoding = request.getCharacterEncoding();
        if (encoding == null || encoding.isBlank()) {
            return StandardCharsets.UTF_8;
        }
        Charset charset;
        try {
            charset = Charset.forName(encoding.trim());
        } catch (IllegalArgumentException e) {
            logger.trace("Unknown request encoding", e);
            throw new UnsupportedEncodingException(encoding);
        }
        if (!charset.canEncode()) {
            // A decode-only charset (x-JISAutoDetect, ISO-2022-CN) cannot measure
            // a body in bytes.
            throw new UnsupportedEncodingException(encoding);
        }
        return charset;
    }

    private static String decode(byte[] bytes, int offset, int length, Charset charset)
            throws CharacterCodingException {
        return charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes, offset, length))
                .toString();
    }

    public static byte[] readEntirelyAsByte(AtmosphereResource r) throws IOException {
        boolean readGetBody = r.getAtmosphereConfig().getInitParameter(ApplicationConfig.READ_GET_BODY, false);
        if (!readGetBody && AtmosphereResourceImpl.class.cast(r).getRequest(false).getMethod().equalsIgnoreCase("GET")) {
            logger.debug("Blocking an I/O read operation from a GET request. To enable GET + body, set {} to true", ApplicationConfig.READ_GET_BODY);
            return new byte[0];
        }

        return forceReadEntirelyAsByte(r);
    }

    /**
     * Reads request body as bytes without respect {@link ApplicationConfig#READ_GET_BODY} parameter
     */
    public static byte[] forceReadEntirelyAsByte(AtmosphereResource r) throws IOException {
        AtmosphereRequest request = r.getRequest();

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
                return body.asString().getBytes(request.getCharacterEncoding());
            } catch (UnsupportedEncodingException e) {
                logger.error("", e);
            }
        } else if (body.hasBytes()) {
            return Arrays.copyOfRange(body.asBytes(), body.byteOffset(), body.byteOffset() + body.byteLength());
        }
        throw new IllegalStateException("No body " + r);
    }

    public static String guestServletPath(AtmosphereConfig config) {
        if (config.getServletConfig() == null) {
            throw new IllegalStateException("Unable to configure jsr356 at that stage");
        }
        return getCleanedServletPath(guestRawServletPath(config));
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

    @SuppressWarnings("unchecked")
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
     * to specify a list of {@link org.atmosphere.cpr.MetaServiceAction actions} to be done on different
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
     * If you don't specify any {@link org.atmosphere.cpr.MetaServiceAction} before a class, then
     * default action will be {@link org.atmosphere.cpr.MetaServiceAction#INSTALL}.
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
    public static Map<String, MetaServiceAction> readServiceFile(final String path) {
        final Map<String, MetaServiceAction> b = new LinkedHashMap<>();

        String line;
        InputStream is = null;
        BufferedReader reader = null;
        MetaServiceAction action = MetaServiceAction.INSTALL;

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
                } else if (line.indexOf('.') == -1) {
                    action = MetaServiceAction.valueOf(line);
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
                return null;
            }
        }
        return realPath;
    }
}