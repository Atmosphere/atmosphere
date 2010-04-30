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
 */
package org.atmosphere.util;

import org.atmosphere.cpr.BroadcastFilter;

/**
 * Simple {@link BroadcastFilter} which automatically filter
 * HTML/HTTP character into proper value, like \n replace by &lt;br&gt;. Using
 * this BroadcastFilter prevent XSS attack.
 *
 * @author Jeanfrancois Arcand
 */
public class XSSHtmlFilter implements BroadcastFilter {

    /**
     * Transform a message into a well formed HTML message.
     *
     * @param o The object to introspect.
     * @return a well formed
     */
    public BroadcastAction filter(Object o) {
        if (o instanceof String) {
            String message = (String) o;

            StringBuffer buffer = new StringBuffer(message.length());

            for (int i = 0; i < message.length(); i++) {
                char c = message.charAt(i);
                switch (c) {
                    case '\b':
                        buffer.append("\\b");
                        break;
                    case '\f':
                        buffer.append("\\f");
                        break;
                    case '\n':
                        buffer.append("<br />");
                        break;
                    case '\r':
                        // ignore
                        break;
                    case '\t':
                        buffer.append("\\t");
                        break;
                    case '\'':
                        buffer.append("\\'");
                        break;
                    case '\"':
                        buffer.append("\\\"");
                        break;
                    case '\\':
                        buffer.append("\\\\");
                        break;
                    case '<':
                        buffer.append("&lt;");
                        break;
                    case '>':
                        buffer.append("&gt;");
                        break;
                    case '&':
                        buffer.append("&amp;");
                        break;
                    default:
                        buffer.append(c);
                }
            }
            return new BroadcastAction(buffer.toString());
        } else {
            return new BroadcastAction(o);
        }
    }
}
