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
package org.atmosphere.util;

import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.CometSupport;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Descriptor for an Atmosphere configuraton file.
 *
 * @author Jerome Dochez (for the version shipped in GlassFish v3).
 * @author Jeanfrancois Arcand
 */
public class AtmosphereConfigReader {

    private Logger logger = LoggerUtils.getLogger();

    final Map<String, String> tuples = new HashMap<String, String>();
    final Map<String, ArrayList<Property>> atmosphereHandlerProperties =  new HashMap<String, ArrayList<Property>>();
    final Map<String, String> broadcasters = new HashMap<String, String>();
    final Map<String, String> broadcasterCache = new HashMap<String, String>();
    private String cometSupportClass = null;
    private String supportSession = "";
    private String[] broadcastFilterClasses;

    /**
     * Create a {@link DocumentBuilderFactory} element from META-INF/atmosphere.xml
     *
     * @param stream
     */
    public AtmosphereConfigReader(InputStream stream) {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        try {
            parse(factory.newDocumentBuilder().parse(stream));
        } catch (SAXException e) {
            logger.log(Level.SEVERE, e.getMessage(), e);
            throw new RuntimeException(e);
        } catch (IOException e) {
            logger.log(Level.SEVERE, e.getMessage(), e);
            throw new RuntimeException(e);
        } catch (ParserConfigurationException e) {
            logger.log(Level.SEVERE, e.getMessage(), e);
            throw new RuntimeException(e);
        }
    }

    /**
     * Parse the META-INF/atmosphere.xml element.
     *
     * @param document
     */
    private void parse(Document document) {
        Element element = document.getDocumentElement();
        NodeList atmosphereHandlers = element.getElementsByTagName("atmosphere-handler");
        for (int i = 0; i < atmosphereHandlers.getLength(); i++) {
            Node atmosphereHandler = atmosphereHandlers.item(i);
            NamedNodeMap attrs = atmosphereHandler.getAttributes();
            NodeList properties = atmosphereHandler.getChildNodes();
            ArrayList<Property> list = new ArrayList<Property>();

            // Read the properties to be set on a AtmosphereHandler
            for (int j = 0; j < properties.getLength(); j++) {
                Node property = properties.item(j);
                NamedNodeMap values = property.getAttributes();
                if (values != null) {
                    list.add(new Property(values.getNamedItem("name").getNodeValue(),
                            values.getNamedItem("value").getNodeValue()));
                }
            }

            if (attrs != null) {
                atmosphereHandlerProperties.put(attrs.getNamedItem("context-root").getNodeValue(), list);

                addAtmosphereHandler(attrs.getNamedItem("context-root").getNodeValue(),
                        attrs.getNamedItem("class-name").getNodeValue());

                if (attrs.getNamedItem("broadcaster") != null) {
                    String broadcasterClass = attrs.getNamedItem("broadcaster").getNodeValue();
                    if (broadcasterClass != null) {
                        broadcasters.put(attrs.getNamedItem("context-root").getNodeValue(), broadcasterClass);
                    }
                }

                if (attrs.getNamedItem("broadcastFilterClasses") != null) {
                    broadcastFilterClasses = attrs.getNamedItem("broadcastFilter").getNodeValue().split(",");
                }

                if (attrs.getNamedItem("broadcasterCache") != null) {
                    String bc = attrs.getNamedItem("broadcasterCache").getNodeValue();
                    if (bc != null) {
                        broadcasterCache.put(attrs.getNamedItem("context-root").getNodeValue(), bc);
                    }
                }
                if (attrs.getNamedItem("comet-support") != null) {
                    cometSupportClass = attrs.getNamedItem("comet-support").getNodeValue();
                }

                if (attrs.getNamedItem("support-session") != null) {
                    supportSession = attrs.getNamedItem("support-session").getNodeValue();
                }
            }
        }
    }

    /**
     * True if the support-session added in atmosphere.xml
     *
     * @return
     */
    public String supportSession() {
        return supportSession;
    }

    /**
     * Add a {@link AtmosphereHandler} to the list of discovered element.
     *
     * @param contextPath The context-path which will map the {@link AtmosphereHandler}
     * @param className   The associated {@lin AtmosphereHandler} class name.
     */
    void addAtmosphereHandler(String contextPath, String className) {
        if (tuples.containsKey(contextPath)) {
            throw new RuntimeException("duplicate context root in configuration :" + contextPath);
        }
        tuples.put(contextPath, className);
    }

    /**
     * Return a {@link Map} which contains the context-oath as a key with its
     * associated {@link AtmosphereHandler}, loaded from META-INF/atmosphere.xml
     *
     * @return
     */
    public Map<String, String> getAtmosphereHandlers() {
        return tuples;
    }

    /**
     * Return a {@link Broadcaster}, or null.
     */
    public String getBroadcasterClass(String contextRoot) {
        return broadcasters.get(contextRoot);
    }

    /**
     * Return a {@link Broadcaster}, or null.
     */
    public String getBroadcasterCache(String contextRoot) {
        return broadcasterCache.get(contextRoot);
    }

    /**
     * Simple Structn representing a <property> element/value.
     */
    public class Property {

        public String name = "";
        public String value = "";

        public Property(String name, String value) {
            this.name = name;
            this.value = value;
        }
    }

    /**
     * Return the properties to be set on {@link AtmosphereHandler}
     *
     * @return An {@link ArrayList} containing the properties to be set on
     *         a {@link AtmosphereHandler}
     */
    public ArrayList<Property> getProperty(String contextRoot) {
        return atmosphereHandlerProperties.get(contextRoot);
    }

    /**
     * Return the {@link CometSupport} implementation
     */
    public String getCometSupportClass() {
        return cometSupportClass;
    }

    public String[] getBroadcastFilterClasses(){
        return broadcastFilterClasses;
    }
}
