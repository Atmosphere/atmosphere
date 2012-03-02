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
package org.atmosphere.samples.chat;

import org.atmosphere.cpr.BroadcastFilter;

/**
 * Simple {@link org.atmosphere.cpr.BroadcastFilter} that produce jsonp String.
 *
 * @author Jeanfrancois Arcand
 */
public class JsonpFilter implements BroadcastFilter {

    private static final String BEGIN_SCRIPT_TAG = "<script type='text/javascript'>\n";
    private static final String END_SCRIPT_TAG = "</script>\n";

    public BroadcastAction filter(Object originalMessage, Object o) {
        if (o instanceof String) {
            String m = (String) o;
            String name = m;
            String message = "";

            if (m.indexOf("__") > 0) {
                name = m.substring(0, m.indexOf("__"));
                message = m.substring(m.indexOf("__") + 2);
            }

            return new BroadcastAction(BEGIN_SCRIPT_TAG + "window.parent.app.update({ name: \""
                    + name + "\", message: \"" + message + "\" });\n" + END_SCRIPT_TAG);
        } else {
            return new BroadcastAction(o);
        }
    }
}
