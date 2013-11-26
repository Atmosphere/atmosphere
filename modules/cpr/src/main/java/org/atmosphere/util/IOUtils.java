/*
 * Copyright 2013 Jeanfrancois Arcand
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

import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.Servlet;
import javax.servlet.ServletRegistration;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.Map;

public class IOUtils {
    private final static Logger logger = LoggerFactory.getLogger(IOUtils.class);

    public static StringBuilder readEntirely(AtmosphereResource r) {
        StringBuilder stringBuilder = new StringBuilder();
        BufferedReader bufferedReader = null;
        try {
            try {
                InputStream inputStream = r.getRequest().getInputStream();
                if (inputStream != null) {
                    bufferedReader = new BufferedReader(new InputStreamReader(inputStream));
                }
            } catch (IllegalStateException ex) {
                logger.trace("", ex);
                Reader reader = r.getRequest().getReader();
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
        return stringBuilder;
    }

    public static String guestServletPath(AtmosphereFramework framework, Class<? extends Servlet> clazz, Class<?> callee) {
        String servletPath = "";
        try {
            Map<String, ? extends ServletRegistration> m = framework.getServletContext().getServletRegistrations();
            for (Map.Entry<String, ? extends ServletRegistration> e : m.entrySet()) {
                if (clazz.isAssignableFrom(loadClass(callee, e.getValue().getClassName()))) {
                    servletPath = "/" + e.getValue().getMappings().iterator().next().replace("/", "").replace("*", "");
                    break;
                }
            }
        } catch (Exception ex) {
            logger.trace("", ex);
        }
        return servletPath;
    }

    public static Class<?> loadClass(Class thisClass, String className) throws Exception {
        try {
            return Thread.currentThread().getContextClassLoader().loadClass(className);
        } catch (Throwable t) {
            return thisClass.getClassLoader().loadClass(className);
        }
    }

}
