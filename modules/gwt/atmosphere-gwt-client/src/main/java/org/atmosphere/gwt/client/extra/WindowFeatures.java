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
package org.atmosphere.gwt.client.extra;

import java.util.HashMap;
import java.util.Map;

/**
 * @see <a href='https://developer.mozilla.org/en/DOM/window.open'>here</a>
 */
public class WindowFeatures {

    private Map<String, Integer> intValues = new HashMap<String, Integer>();
    private Map<String, Boolean> options = new HashMap<String, Boolean>();

    public WindowFeatures() {
    }

    public WindowFeatures(Integer left, Integer top, Integer height, Integer width) {
        setLeft(left);
        setTop(top);
        setWidth(width);
        setHeight(height);
    }

    public WindowFeatures(Boolean menubar, Boolean toolbar, Boolean location, Boolean personalbar, Boolean status) {
        setMenubar(menubar);
        setToolbar(toolbar);
        setLocation(location);
        setPersonalbar(personalbar);
        setStatus(status);
    }
    
    public WindowFeatures set(String name, Integer value) {
        intValues.put(name, value);
        return this;
    }
    
    public WindowFeatures set(String name, Boolean option) {
        options.put(name, option);
        return this;
    }

    public WindowFeatures setDependent(Boolean dependent) {
        options.put("dependent", dependent);
        return this;
    }

    public WindowFeatures setDialog(Boolean dialog) {
        options.put("dialog", dialog);
        return this;
    }

    public WindowFeatures setFullscreen(Boolean fullscreen) {
        options.put("fullscreen", fullscreen);
        return this;
    }

    public WindowFeatures setHeight(Integer height) {
        intValues.put("height", height);
        return this;
    }

    public WindowFeatures setLeft(Integer left) {
        intValues.put("left", left);
        return this;
    }

    public WindowFeatures setLocation(Boolean location) {
        options.put("location", location);
        return this;
    }

    public WindowFeatures setMenubar(Boolean menubar) {
        options.put("menubar", menubar);
        return this;
    }

    public WindowFeatures setMinimizable(Boolean minimizable) {
        options.put("minimizable", minimizable);
        return this;
    }

    public WindowFeatures setPersonalbar(Boolean personalbar) {
        options.put("personalbar", personalbar);
        return this;
    }

    public WindowFeatures setResizable(Boolean resizable) {
        options.put("resizable", resizable);
        return this;
    }

    public WindowFeatures setScrollbars(Boolean scrollbars) {
        options.put("scrollbars", scrollbars);
        return this;
    }

    public WindowFeatures setStatus(Boolean status) {
        options.put("status", status);
        return this;
    }

    public WindowFeatures setToolbar(Boolean toolbar) {
        options.put("toolbar", toolbar);
        return this;
    }

    public WindowFeatures setTop(Integer top) {
        intValues.put("top", top);
        return this;
    }

    public WindowFeatures setWidth(Integer width) {
        intValues.put("width", width);
        return this;
    }
    
    public String toString() {
        PropertyBuilder b = new PropertyBuilder();
        for (Map.Entry<String, Integer> e : intValues.entrySet()) {
            b.add(e.getKey(), e.getValue());
        }
        for (Map.Entry<String, Boolean> e : options.entrySet()) {
            b.add(e.getKey(), e.getValue());
        }
        return b.toString();
    }
    private static class PropertyBuilder {
        StringBuilder result = new StringBuilder();
        public PropertyBuilder add(String name, Boolean value) {
            if (value == null) {
                return this;
            }
            add(name, value ? "yes":"no");
            return this;
        }
        public PropertyBuilder add(String name, Integer value) {
            if (value == null) {
                return this;
            }
            add(name, value.toString());
            return this;
        }
        public PropertyBuilder add(String name, String value) {
            if (value == null) {
                return this;
            }
            if (result.length() > 0) {
                result.append(",");
            }
            result.append(name).append('=').append(value);
            return this;
        }
        public String toString() {
            return result.toString();
        }
    }
}
