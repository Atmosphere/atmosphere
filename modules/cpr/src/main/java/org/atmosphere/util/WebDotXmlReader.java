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

import org.atmosphere.cpr.AtmosphereServlet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;

/**
 * web.xml reader.
 *
 * @author Jeanfrancois Arcand
 */
public class WebDotXmlReader {

    private static final Logger logger = LoggerFactory.getLogger(WebDotXmlReader.class);

    private static final String ATMOSPHERE_SERVLET = AtmosphereServlet.class.getName();

    private final ArrayList<String> mappings = new ArrayList<String>();

    /**
     * Create a {@link DocumentBuilderFactory} element from WEB-INF/web.xml
     *
     * @param stream
     */
    public WebDotXmlReader(InputStream stream) {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        try {
            parse(factory.newDocumentBuilder().parse(stream));
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
     * Parse the web.xml element.
     *
     * @param document
     */
    private void parse(Document document) {
        Element element = document.getDocumentElement();
        NodeList servlets = element.getElementsByTagName("servlet");
        String atmosphereServletName = null;
        for (int i = 0; i < servlets.getLength(); i++) {
            Node m = servlets.item(i);
            NodeList list = m.getChildNodes();
            for (int j = 0; j < list.getLength(); j++) {
                Node n = list.item(j);
                if (n.getNodeName().equals("servlet-name")) {
                    atmosphereServletName = n.getFirstChild().getNodeValue();
                }

                if (n.getNodeName().equals("servlet-class")) {
                    if (n.getFirstChild().getNodeValue().equals(ATMOSPHERE_SERVLET)) {
                        break;
                    }
                }
            }
        }

        NodeList servletMappings = element.getElementsByTagName("servlet-mapping");
        for (int i = 0; i < servletMappings.getLength(); i++) {
            Node m = servletMappings.item(i);
            NodeList list = m.getChildNodes();

            String urlMapping = null;
            String servletName = null;
            for (int j = 0; j < list.getLength(); j++) {
                Node n = list.item(j);
                if (n.getNodeName().equals("servlet-name")) {
                    servletName = n.getFirstChild().getNodeValue();
                    if (!servletName.equals(atmosphereServletName)) {
                        servletName = null;
                    }
                }

                if (n.getNodeName().equals("url-pattern")) {
                    urlMapping = n.getFirstChild().getNodeValue();
                }

                if (servletName != null && urlMapping != null) {
                    mappings.add(urlMapping);
                    servletName = null;
                    urlMapping = null;
                }
            }
        }
    }

    /**
     * Return an {@link ArrayList} which maps to the {@link AtmosphereServlet}
     */
    public ArrayList<String> getMappings() {
        return mappings;
    }
}
