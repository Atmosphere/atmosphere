/*
 * Copyright 2008-2020 Async-IO.org
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
    public BroadcastAction filter(String broadcasterId, Object originalMessage, Object o) {
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
