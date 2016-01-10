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

/*
 * This file incorporates work covered by the following copyright and
 * permission notice:
 *
 * Copyright 2004 The Apache Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.atmosphere.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.Hashtable;
import java.util.StringTokenizer;
import java.util.Vector;

/**
 * Utils for introspection and reflection
 */
public final class IntrospectionUtils {

    private static final Logger logger = LoggerFactory.getLogger(IntrospectionUtils.class);

    /**
     * Call execute() - any ant-like task should work
     */
    public static void execute(Object proxy, String method) throws Exception {
        Method executeM = null;
        Class<?> c = proxy.getClass();
        Class<?> params[] = new Class[0];
        //    params[0]=args.getClass();
        executeM = findMethod(c, method, params);
        if (executeM == null) {
            throw new RuntimeException("No execute in " + proxy.getClass());
        }
        executeM.invoke(proxy, (Object[]) null);//new Object[] { args });
    }

    /**
     * Call void setAttribute( String ,Object )
     */
    public static void setAttribute(Object proxy, String name, Object value)
            throws Exception {
        if (proxy instanceof AttributeHolder) {
            ((AttributeHolder) proxy).setAttribute(name, value);
            return;
        }

        Method executeM = null;
        Class<?> c = proxy.getClass();
        Class<?> params[] = new Class[2];
        params[0] = String.class;
        params[1] = Object.class;
        executeM = findMethod(c, "setAttribute", params);
        if (executeM == null) {
            logger.debug("No setAttribute in {}", proxy.getClass());
            return;
        }

        logger.debug("Setting {}={} in proxy: {}", new Object[]{name, value, proxy});
        executeM.invoke(proxy, new Object[]{name, value});
        return;
    }

    /**
     * Call void getAttribute( String )
     */
    public static Object getAttribute(Object proxy, String name) throws Exception {
        Class<?> c = proxy.getClass();
        Class<?> params[] = new Class[1];
        params[0] = String.class;
        Method executeM = findMethod(c, "getAttribute", params);
        if (executeM == null) {
            logger.debug("No getAttribute in {}", proxy.getClass());
            return null;
        }
        return executeM.invoke(proxy, new Object[]{name});
    }

    /**
     * Construct a URLClassLoader. Will compile and work in JDK1.1 too.
     */
    public static ClassLoader getURLClassLoader(URL urls[], ClassLoader parent) {
        try {
            Class<?> urlCL = Class.forName("java.net.URLClassLoader");
            Class<?> paramT[] = new Class[2];
            paramT[0] = urls.getClass();
            paramT[1] = ClassLoader.class;
            Method m = findMethod(urlCL, "newInstance", paramT);
            if (m == null) {
                return null;
            }

            ClassLoader cl = (ClassLoader) m.invoke(urlCL, new Object[]{urls, parent});
            return cl;
        } catch (ClassNotFoundException ex) {
            // jdk1.1
            return null;
        } catch (Exception ex) {
            logger.error("failed getting URLClassLoader", ex);
            return null;
        }
    }


    /**
     * Find a method with the right name If found, call the method ( if param is
     * int or boolean we'll convert value to the right type before) - that means
     * you can have setDebug(1).
     */
    final public static boolean setProperty(Object o, String name, String value) {
        String setter = "set" + capitalize(name);
        return invokeProperty(o, setter, name, value);
    }

    final public static boolean addProperty(Object o, String name, String value) {
        String setter = "add" + capitalize(name);
        return invokeProperty(o, setter, name, value);
    }

    final static public boolean invokeProperty(Object object, String setter, String name, String value) {
        try {
            Method methods[] = findMethods(object.getClass());
            Method setPropertyMethodVoid = null;
            Method setPropertyMethodBool = null;

            // First, the ideal case - a setFoo( String ) method
            for (int i = 0; i < methods.length; i++) {
                Class<?> paramT[] = methods[i].getParameterTypes();
                if (setter.equals(methods[i].getName()) && paramT.length == 1
                        && "java.lang.String".equals(paramT[0].getName())) {

                    methods[i].invoke(object, new Object[]{value});
                    return true;
                }
            }

            // Try a setFoo ( int ) or ( boolean )
            for (int i = 0; i < methods.length; i++) {
                boolean ok = true;
                if (setter.equals(methods[i].getName())
                        && methods[i].getParameterTypes().length == 1) {

                    // match - find the type and invoke it
                    Class<?> paramType = methods[i].getParameterTypes()[0];
                    Object params[] = new Object[1];

                    // Try a setFoo ( int )
                    if ("java.lang.Integer".equals(paramType.getName())
                            || "int".equals(paramType.getName())) {
                        try {
                            params[0] = new Integer(value);
                        } catch (NumberFormatException ex) {
                            ok = false;
                        }
                        // Try a setFoo ( long )
                    } else if ("java.lang.Long".equals(paramType.getName())
                            || "long".equals(paramType.getName())) {
                        try {
                            params[0] = new Long(value);
                        } catch (NumberFormatException ex) {
                            ok = false;
                        }

                        // Try a setFoo ( boolean )
                    } else if ("java.lang.Boolean".equals(paramType.getName())
                            || "boolean".equals(paramType.getName())) {
                        params[0] = new Boolean(value);

                        // Try a setFoo ( InetAddress )
                    } else if ("java.net.InetAddress".equals(paramType
                            .getName())) {
                        try {
                            params[0] = InetAddress.getByName(value);
                        } catch (UnknownHostException exc) {
                            debug("Unable to resolve host name:" + value);
                            ok = false;
                        }

                        // Unknown type
                    } else {
                        debug("Unknown type " + paramType.getName());
                    }

                    if (ok) {
                        methods[i].invoke(object, params);
                        return true;
                    }
                }

                // save "setProperty" for later
                if ("setProperty".equals(methods[i].getName())) {
                    if (methods[i].getReturnType() == Boolean.TYPE) {
                        setPropertyMethodBool = methods[i];
                    } else {
                        setPropertyMethodVoid = methods[i];
                    }

                }
            }

            // Ok, no setXXX found, try a setProperty("name", "value")
            if (setPropertyMethodBool != null || setPropertyMethodVoid != null) {
                Object params[] = new Object[2];
                params[0] = name;
                params[1] = value;
                if (setPropertyMethodBool != null) {
                    try {
                        return (Boolean) setPropertyMethodBool.invoke(object, params);
                    } catch (IllegalArgumentException biae) {
                        //the boolean method had the wrong
                        //parameter types. lets try the other
                        if (setPropertyMethodVoid != null) {
                            setPropertyMethodVoid.invoke(object, params);
                            return true;
                        } else {
                            throw biae;
                        }
                    }
                } else {
                    setPropertyMethodVoid.invoke(object, params);
                    return true;
                }
            }

        } catch (IllegalArgumentException e) {
            logger.info("failed, object: " + object + ", setter: " + setter + ", value: " + value, e);
        } catch (Exception e) {
            if (dbg > 0) {
                debug(e.getClass().getSimpleName() + " for " + object.getClass() + " " + setter + "=" + value + ")");
            }
            if (dbg > 1) {
                logger.debug("", e);
            }
        }

        return false;
    }

    public static Object getProperty(Object object, String name) {
        String getter = "get" + capitalize(name);
        String isGetter = "is" + capitalize(name);

        try {
            Method methods[] = findMethods(object.getClass());
            Method getPropertyMethod = null;

            // First, the ideal case - a getFoo() method
            for (int i = 0; i < methods.length; i++) {
                Class<?> paramT[] = methods[i].getParameterTypes();
                if (getter.equals(methods[i].getName()) && paramT.length == 0) {
                    return methods[i].invoke(object, (Object[]) null);
                }
                if (isGetter.equals(methods[i].getName()) && paramT.length == 0) {
                    return methods[i].invoke(object, (Object[]) null);
                }

                if ("getProperty".equals(methods[i].getName())) {
                    getPropertyMethod = methods[i];
                }
            }

            // Ok, no setXXX found, try a getProperty("name")
            if (getPropertyMethod != null) {
                Object params[] = new Object[1];
                params[0] = name;
                return getPropertyMethod.invoke(object, params);
            }

        } catch (IllegalArgumentException e) {
            logger.info("failed, object: " + object + ", name: " + name, e);
        } catch (Exception e) {
            if (dbg > 0) {
                debug(e.getClass().getSimpleName() + " for " + object.getClass() + " " + name + ")");
            }
            if (dbg > 1) {
                logger.debug("", e);
            }
        }

        return null;
    }

    /**
     */
    public static void setProperty(Object object, String name) {
        String setter = "set" + capitalize(name);
        try {
            Method methods[] = findMethods(object.getClass());
            Method setPropertyMethod = null;
            // find setFoo() method
            for (int i = 0; i < methods.length; i++) {
                Class<?> paramT[] = methods[i].getParameterTypes();
                if (setter.equals(methods[i].getName()) && paramT.length == 0) {
                    methods[i].invoke(object, new Object[]{});
                    return;
                }
            }
        } catch (Exception e) {
            if (dbg > 0) {
                debug("Exception for " + object.getClass() + " " + name);
            }
            if (dbg > 1) {
                logger.debug("", e);
            }
        }
    }

    /**
     * Replace ${NAME} with the property value
     */
    public static String replaceProperties(String value,
                                           Hashtable<String, String> staticProp, PropertySource dynamicProp[]) {
        if (value.indexOf("$") < 0) {
            return value;
        }
        StringBuilder sb = new StringBuilder();
        int prev = 0;
        // assert value!=nil
        int pos;
        while ((pos = value.indexOf("$", prev)) >= 0) {
            if (pos > 0) {
                sb.append(value.substring(prev, pos));
            }
            if (pos == (value.length() - 1)) {
                sb.append('$');
                prev = pos + 1;
            } else if (value.charAt(pos + 1) != '{') {
                sb.append('$');
                prev = pos + 1; // XXX
            } else {
                int endName = value.indexOf('}', pos);
                if (endName < 0) {
                    sb.append(value.substring(pos));
                    prev = value.length();
                    continue;
                }
                String n = value.substring(pos + 2, endName);
                String v = null;
                if (staticProp != null) {
                    v = staticProp.get(n);
                }
                if (v == null && dynamicProp != null) {
                    for (int i = 0; i < dynamicProp.length; i++) {
                        v = dynamicProp[i].getProperty(n);
                        if (v != null) {
                            break;
                        }
                    }
                }
                if (v == null)
                    v = "${" + n + "}";

                sb.append(v);
                prev = endName + 1;
            }
        }
        if (prev < value.length())
            sb.append(value.substring(prev));
        return sb.toString();
    }

    /**
     * Reverse of Introspector.decapitalize
     */
    public static String capitalize(String name) {
        if (name == null || name.length() == 0) {
            return name;
        }
        char chars[] = name.toCharArray();
        chars[0] = Character.toUpperCase(chars[0]);
        return new String(chars);
    }

    public static String unCapitalize(String name) {
        if (name == null || name.length() == 0) {
            return name;
        }
        char chars[] = name.toCharArray();
        chars[0] = Character.toLowerCase(chars[0]);
        return new String(chars);
    }

    // -------------------- Class path tools --------------------

    /**
     * Add all the jar files in a dir to the classpath, represented as a Vector
     * of URLs.
     */
    @SuppressWarnings("unchecked")
    public static void addToClassPath(Vector<URL> cpV, String dir) {
        try {
            String cpComp[] = getFilesByExt(dir, ".jar");
            if (cpComp != null) {
                int jarCount = cpComp.length;
                for (int i = 0; i < jarCount; i++) {
                    URL url = getURL(dir, cpComp[i]);
                    if (url != null)
                        cpV.addElement(url);
                }
            }
        } catch (Exception ex) {
            logger.debug("failed to add urls to classpath", ex);
        }
    }

    @SuppressWarnings("unchecked")
    public static void addToolsJar(Vector<URL> v) {
        try {
            // Add tools.jar in any case
            File f = new File(System.getProperty("java.home")
                    + "/../lib/tools.jar");

            if (!f.exists()) {
                // On some systems java.home gets set to the root of jdk.
                // That's a bug, but we can work around and be nice.
                f = new File(System.getProperty("java.home") + "/lib/tools.jar");
                if (f.exists()) {
                    logger.debug("Detected strange java.home value {}, it should point to jre",
                            System.getProperty("java.home"));
                }
            }
            URL url = new URL("file", "", f.getAbsolutePath());

            v.addElement(url);
        } catch (MalformedURLException ex) {
            logger.debug("failed to add tools jar url to vector", ex);
        }
    }

    /**
     * Return all files with a given extension in a dir
     */
    public static String[] getFilesByExt(String ld, String ext) {
        File dir = new File(ld);
        String[] names = null;
        final String lext = ext;
        if (dir.isDirectory()) {
            names = dir.list(new FilenameFilter() {
                public boolean accept(File d, String name) {
                    if (name.endsWith(lext)) {
                        return true;
                    }
                    return false;
                }
            });
        }
        return names;
    }

    /**
     * Construct a file url from a file, using a base dir
     */
    public static URL getURL(String base, String file) {
        try {
            File baseF = new File(base);
            File f = new File(baseF, file);
            String path = f.getCanonicalPath();
            if (f.isDirectory()) {
                path += "/";
            }
            if (!f.exists()) {
                return null;
            }
            return new URL("file", "", path);
        } catch (Exception ex) {
            logger.debug("failed to get url, base: " + base + ", file: " + file, ex);
            return null;
        }
    }

    /**
     * Add elements from the classpath <i>cp </i> to a Vector <i>jars </i> as
     * file URLs (We use Vector for JDK 1.1 compat).
     * <p/>
     *
     * @param jars The jar list
     * @param cp   a String classpath of directory or jar file elements
     *             separated by path.separator delimiters.
     * @throws IOException           If an I/O error occurs
     * @throws MalformedURLException Doh ;)
     */
    @SuppressWarnings("unchecked")
    public static void addJarsFromClassPath(Vector<URL> jars, String cp)
            throws IOException, MalformedURLException {
        String sep = System.getProperty("path.separator");
        String token;
        StringTokenizer st;
        if (cp != null) {
            st = new StringTokenizer(cp, sep);
            while (st.hasMoreTokens()) {
                File f = new File(st.nextToken());
                String path = f.getCanonicalPath();
                if (f.isDirectory()) {
                    path += "/";
                }
                URL url = new URL("file", "", path);
                if (!jars.contains(url)) {
                    jars.addElement(url);
                }
            }
        }
    }

    /**
     * Return a URL[] that can be used to construct a class loader
     */
    public static URL[] getClassPath(Vector<URL> v) {
        URL[] urls = new URL[v.size()];
        for (int i = 0; i < v.size(); i++) {
            urls[i] = v.elementAt(i);
        }
        return urls;
    }

    /**
     * Construct a URL classpath from files in a directory, a cpath property,
     * and tools.jar.
     */
    @SuppressWarnings("unchecked")
    public static URL[] getClassPath(String dir, String cpath,
                                     String cpathProp, boolean addTools) throws IOException,
            MalformedURLException {
        Vector<URL> jarsV = new Vector<URL>();
        if (dir != null) {
            // Add dir/classes first, if it exists
            URL url = getURL(dir, "classes");
            if (url != null)
                jarsV.addElement(url);
            addToClassPath(jarsV, dir);
        }

        if (cpath != null)
            addJarsFromClassPath(jarsV, cpath);

        if (cpathProp != null) {
            String cpath1 = System.getProperty(cpathProp);
            addJarsFromClassPath(jarsV, cpath1);
        }

        if (addTools)
            addToolsJar(jarsV);

        return getClassPath(jarsV);
    }

    // -------------------- Mapping command line params to setters

    public static boolean processArgs(Object proxy, String args[])
            throws Exception {
        String args0[] = null;
        if (null != findMethod(proxy.getClass(), "getOptions1", new Class[]{})) {
            args0 = (String[]) callMethod0(proxy, "getOptions1");
        }

        if (args0 == null) {
            //args0=findVoidSetters(proxy.getClass());
            args0 = findBooleanSetters(proxy.getClass());
        }
        Hashtable<String, String> h = null;
        if (null != findMethod(proxy.getClass(), "getOptionAliases",
                new Class[]{})) {
            h = (Hashtable<String, String>) callMethod0(proxy, "getOptionAliases");
        }
        return processArgs(proxy, args, args0, null, h);
    }

    public static boolean processArgs(Object proxy, String args[],
                                      String args0[], String args1[],
                                      Hashtable<String, String> aliases) throws Exception {
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("-"))
                arg = arg.substring(1);
            if (aliases != null && aliases.get(arg) != null)
                arg = aliases.get(arg);

            if (args0 != null) {
                boolean set = false;
                for (int j = 0; j < args0.length; j++) {
                    if (args0[j].equalsIgnoreCase(arg)) {
                        setProperty(proxy, args0[j], "true");
                        set = true;
                        break;
                    }
                }
                if (set)
                    continue;
            }
            if (args1 != null) {
                for (int j = 0; j < args1.length; j++) {
                    if (args1[j].equalsIgnoreCase(arg)) {
                        i++;
                        if (i >= args.length)
                            return false;
                        setProperty(proxy, arg, args[i]);
                        break;
                    }
                }
            } else {
                // if args1 is not specified,assume all other options have param
                i++;
                if (i >= args.length)
                    return false;
                setProperty(proxy, arg, args[i]);
            }

        }
        return true;
    }

    // -------------------- other utils --------------------

    public static void clear() {
        objectMethods.clear();
    }

    @SuppressWarnings("unchecked")
    public static String[] findVoidSetters(Class<?> c) {
        Method m[] = findMethods(c);
        if (m == null)
            return null;
        Vector<String> v = new Vector<String>();
        for (int i = 0; i < m.length; i++) {
            if (m[i].getName().startsWith("set")
                    && m[i].getParameterTypes().length == 0) {
                String arg = m[i].getName().substring(3);
                v.addElement(unCapitalize(arg));
            }
        }
        String s[] = new String[v.size()];
        for (int i = 0; i < s.length; i++) {
            s[i] = (String) v.elementAt(i);
        }
        return s;
    }

    @SuppressWarnings("unchecked")
    public static String[] findBooleanSetters(Class<?> c) {
        Method m[] = findMethods(c);
        if (m == null)
            return null;
        Vector<String> v = new Vector<String>();
        for (int i = 0; i < m.length; i++) {
            if (m[i].getName().startsWith("set")
                    && m[i].getParameterTypes().length == 1
                    && "boolean".equalsIgnoreCase(m[i].getParameterTypes()[0]
                    .getName())) {
                String arg = m[i].getName().substring(3);
                v.addElement(unCapitalize(arg));
            }
        }
        String s[] = new String[v.size()];
        for (int i = 0; i < s.length; i++) {
            s[i] = v.elementAt(i);
        }
        return s;
    }

    static Hashtable<Class<?>, Method[]> objectMethods =
            new Hashtable<Class<?>, Method[]>();

    @SuppressWarnings("unchecked")
    public static Method[] findMethods(Class<?> c) {
        Method methods[] = (Method[]) objectMethods.get(c);
        if (methods != null)
            return methods;

        methods = c.getMethods();
        objectMethods.put(c, methods);
        return methods;
    }

    public static Method findMethod(Class<?> c, String name,
                                    Class<?> params[]) {
        Method methods[] = findMethods(c);
        if (methods == null)
            return null;
        for (int i = 0; i < methods.length; i++) {
            if (methods[i].getName().equals(name)) {
                Class<?> methodParams[] = methods[i].getParameterTypes();
                if (methodParams == null)
                    if (params == null || params.length == 0)
                        return methods[i];
                if (params == null)
                    if (methodParams == null || methodParams.length == 0)
                        return methods[i];
                if (params.length != methodParams.length)
                    continue;
                boolean found = true;
                for (int j = 0; j < params.length; j++) {
                    if (params[j] != methodParams[j]) {
                        found = false;
                        break;
                    }
                }
                if (found)
                    return methods[i];
            }
        }
        return null;
    }

    /**
     * Test if the object implements a particular
     * method
     */
    public static boolean hasHook(Object obj, String methodN) {
        try {
            Method myMethods[] = findMethods(obj.getClass());
            for (int i = 0; i < myMethods.length; i++) {
                if (methodN.equals(myMethods[i].getName())) {
                    // check if it's overriden
                    Class<?> declaring = myMethods[i].getDeclaringClass();
                    Class<?> parentOfDeclaring = declaring.getSuperclass();
                    // this works only if the base class doesn't extend
                    // another class.

                    // if the method is declared in a top level class
                    // like BaseInterceptor parent is Object, otherwise
                    // parent is BaseInterceptor or an intermediate class
                    if (!"java.lang.Object".equals(parentOfDeclaring.getName())) {
                        return true;
                    }
                }
            }
        } catch (Exception ex) {
            logger.debug("hasHook() failed", ex);
        }
        return false;
    }

    public static void callMain(Class<?> c, String args[]) throws Exception {
        Class<?> p[] = new Class[1];
        p[0] = args.getClass();
        @SuppressWarnings("unchecked")
        Method m = c.getMethod("main", p);
        m.invoke(c, new Object[]{args});
    }

    public static Object callMethod1(Object target, String methodN,
                                     Object param1, String typeParam1, ClassLoader cl) throws Exception {
        if (target == null || param1 == null) {
            debug("Assert: Illegal params " + target + " " + param1);
        }
        if (dbg > 0)
            debug("callMethod1 " + target.getClass().getName() + " " + param1.getClass().getName() + " " +
                    typeParam1);

        Class<?> params[] = new Class[1];
        if (typeParam1 == null) {
            params[0] = param1.getClass();
        } else {
            params[0] = cl.loadClass(typeParam1);
        }
        Method m = findMethod(target.getClass(), methodN, params);
        if (m == null) {
            throw new NoSuchMethodException(target.getClass().getName() + " " + methodN);
        }
        return m.invoke(target, new Object[]{param1});
    }

    public static Object callMethod0(Object target, String methodN)
            throws Exception {
        if (target == null) {
            debug("Assert: Illegal params " + target);
            return null;
        }
        if (dbg > 0) {
            debug("callMethod0 " + target.getClass().getName() + "." + methodN);
        }

        Class params[] = new Class[0];
        Method m = findMethod(target.getClass(), methodN, params);
        if (m == null) {
            throw new NoSuchMethodException(target.getClass().getName() + " " + methodN);
        }
        return m.invoke(target, emptyArray);
    }

    static Object[] emptyArray = new Object[]{};

    public static Object callMethodN(Object target, String methodN,
                                     Object params[], Class<?> typeParams[]) throws Exception {
        Method m = null;
        m = findMethod(target.getClass(), methodN, typeParams);
        if (m == null) {
            debug("Can't find method " + methodN + " in " + target + " CLASS " + target.getClass());
            return null;
        }
        Object o = m.invoke(target, params);

        if (dbg > 0) {
            // debug
            StringBuffer sb = new StringBuffer();
            sb.append("" + target.getClass().getName() + "." + methodN + "( ");
            for (int i = 0; i < params.length; i++) {
                if (i > 0)
                    sb.append(", ");
                sb.append(params[i]);
            }
            sb.append(")");
            debug(sb.toString());
        }
        return o;
    }

    public static Object convert(String object, Class<?> paramType) {
        Object result = null;
        if ("java.lang.String".equals(paramType.getName())) {
            result = object;
        } else if ("java.lang.Integer".equals(paramType.getName())
                || "int".equals(paramType.getName())) {
            try {
                result = new Integer(object);
            } catch (NumberFormatException ex) {
            }
            // Try a setFoo ( boolean )
        } else if ("java.lang.Boolean".equals(paramType.getName())
                || "boolean".equals(paramType.getName())) {
            result = new Boolean(object);

            // Try a setFoo ( InetAddress )
        } else if ("java.net.InetAddress".equals(paramType
                .getName())) {
            try {
                result = InetAddress.getByName(object);
            } catch (UnknownHostException exc) {
                debug("Unable to resolve host name:" + object);
            }

            // Unknown type
        } else {
            debug("Unknown type " + paramType.getName());
        }
        if (result == null) {
            throw new IllegalArgumentException("Can't convert argument: " + object);
        }
        return result;
    }

    // -------------------- Get property --------------------
    // This provides a layer of abstraction

    public static interface PropertySource {

        public String getProperty(String key);

    }

    public static interface AttributeHolder {

        public void setAttribute(String key, Object o);

    }

    // debug --------------------
    static final int dbg = 0;

    static void debug(String s) {
        logger.debug("IntrospectionUtils: {}", s);
    }
}
