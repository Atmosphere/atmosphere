/*
 * Copyright 2012 Jeanfrancois Arcand
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

import org.atmosphere.config.ApplicationConfiguration;
import org.atmosphere.config.AtmosphereHandlerConfig;
import org.atmosphere.config.AtmosphereHandlerProperty;
import org.atmosphere.config.FrameworkConfiguration;
import org.atmosphere.cpr.AtmosphereConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;

/**
 * Descriptor for an Atmosphere configuraton file.
 *
 * @author Jerome Dochez (for the version shipped in GlassFish v3).
 * @author Jeanfrancois Arcand
 * @author Sebastien Dionne : sebastien.dionne@gmail.com
 */
public class AtmosphereConfigReader {

    private static final AtmosphereConfigReader instance = new AtmosphereConfigReader();
    private static final Logger logger = LoggerFactory.getLogger(AtmosphereConfigReader.class);

    private AtmosphereConfigReader() {
    }

    public AtmosphereConfig parse(AtmosphereConfig config, String filename) throws FileNotFoundException {

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        try {
            return parse(config, factory.newDocumentBuilder().parse(filename));
        } catch (SAXException e) {
            logger.error(e.getMessage(), e);
            throw new RuntimeException(e);
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
            throw new RuntimeException(e);
        } catch (ParserConfigurationException e) {
            logger.error(e.getMessage(), e);
            throw new RuntimeException(e);
        }

    }

    public AtmosphereConfig parse(AtmosphereConfig config, InputStream stream) throws FileNotFoundException {

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        try {
            return parse(config, factory.newDocumentBuilder().parse(stream));
        } catch (SAXException e) {
            logger.error(e.getMessage(), e);
            throw new RuntimeException(e);
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
            throw new RuntimeException(e);
        } catch (ParserConfigurationException e) {
            logger.error(e.getMessage(), e);
            throw new RuntimeException(e);
        }

    }

    /**
     * Parse the atmosphere-handlers element.
     *
     * @param document
     */
    private AtmosphereConfig parse(AtmosphereConfig config, Document document) {

        Element element = document.getDocumentElement();
        NodeList atmosphereHandlers = element.getElementsByTagName("atmosphere-handler");
        for (int i = 0; i < atmosphereHandlers.getLength(); i++) {
            AtmosphereHandlerConfig atmoHandler = new AtmosphereHandlerConfig();

            Node root = atmosphereHandlers.item(i);

            // parse Attributes
            for (int j = 0; j < root.getAttributes().getLength(); j++) {

                Node attribute = root.getAttributes().item(j);

                if (attribute.getNodeName().equals("support-session")) {
                    atmoHandler.setSupportSession(attribute.getFirstChild().getNodeValue());
                } else if (attribute.getNodeName().equals("context-root")) {
                    atmoHandler.setContextRoot(attribute.getFirstChild().getNodeValue());
                } else if (attribute.getNodeName().equals("class-name")) {
                    atmoHandler.setClassName(attribute.getFirstChild().getNodeValue());
                } else if (attribute.getNodeName().equals("broadcaster")) {
                    atmoHandler.setBroadcaster(attribute.getFirstChild().getNodeValue());
                } else if (attribute.getNodeName().equals("broadcasterCache")) {
                    atmoHandler.setBroadcasterCache(attribute.getFirstChild().getNodeValue());
                } else if (attribute.getNodeName().equals("broadcastFilterClasses")) {
                    String[] values = attribute.getFirstChild().getNodeValue().split(",");
                    for (String value : values) {
                        atmoHandler.getBroadcastFilterClasses().add(value);
                    }
                } else if (attribute.getNodeName().equals("comet-support")) {
                    atmoHandler.setCometSupport(attribute.getFirstChild().getNodeValue());
                } else if (attribute.getNodeName().equals("interceptorClasses")) {
                    String[] values = attribute.getFirstChild().getNodeValue().split(",");
                    for (String value : values) {
                        atmoHandler.getAtmosphereInterceptorClasses().add(value);
                    }
                }
            }

            NodeList list = root.getChildNodes();

            for (int j = 0; j < list.getLength(); j++) {
                Node n = list.item(j);
                if (n.getNodeName().equals("property")) {
                    String param = n.getAttributes().getNamedItem("name").getNodeValue();
                    String value = n.getAttributes().getNamedItem("value").getNodeValue();

                    atmoHandler.getProperties().add(new AtmosphereHandlerProperty(param, value));
                } else if (n.getNodeName().equals("applicationConfig")) {

                    String param = null;
                    String value = null;
                    for (int k = 0; k < n.getChildNodes().getLength(); k++) {

                        Node n2 = n.getChildNodes().item(k);

                        if (n2.getNodeName().equals("param-name")) {
                            param = n2.getFirstChild().getNodeValue();
                        } else if (n2.getNodeName().equals("param-value")) {
                            if (n2.getFirstChild() != null) {
                                value = n2.getFirstChild().getNodeValue();
                            }
                        }

                    }

                    if (param != null) {
                        atmoHandler.getApplicationConfig().add(new ApplicationConfiguration(param, value));
                    }

                } else if (n.getNodeName().equals("frameworkConfig")) {
                    String param = null;
                    String value = null;
                    for (int k = 0; k < n.getChildNodes().getLength(); k++) {

                        Node n2 = n.getChildNodes().item(k);

                        if (n2.getNodeName().equals("param-name")) {
                            param = n2.getFirstChild().getNodeValue();
                        } else if (n2.getNodeName().equals("param-value")) {
                            if (n2.getFirstChild() != null) {
                                value = n2.getFirstChild().getNodeValue();
                            }
                        }

                    }

                    if (param != null) {
                        atmoHandler.getFrameworkConfig().add(
                                new FrameworkConfiguration(param, value));
                    }

                }

            }

            config.getAtmosphereHandlerConfig().add(atmoHandler);
        }

        return config;

    }

    public static AtmosphereConfigReader getInstance() {
        return instance;
    }
}