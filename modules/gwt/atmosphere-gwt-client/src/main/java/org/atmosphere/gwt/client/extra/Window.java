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

import com.google.gwt.core.client.JavaScriptException;
import com.google.gwt.core.client.JavaScriptObject;

/**
 *
 * @author p.havelaar
 */
public final class Window extends JavaScriptObject {

    public static native Window current() /*-{
        return $wnd;
    }-*/;
    
    public Window open(String url, String name, WindowFeatures features) {
        return open(url, name, features.toString());
    }

    /**
     * Opens a new browser window. The "name" and "features" arguments are
     * specified <a href=
     * 'https://developer.mozilla.org/en/DOM/window.open'>here</a>.
     * 
     * @param url the URL that the new window will display
     * @param name the name of the window (e.g. "_blank")
     * @param features the features to be enabled/disabled on this window
     */
    public native Window open(String url, String name, String features) /*-{
        return this.open(url, name, features);
    }-*/;

    public native void close() /*-{
        this.close();
    }-*/;

    public native void set(String name, boolean flag) /*-{
        if (!this.atts) {
            this.atts = {};
        }
        this.atts[name] = flag;
    }-*/;
    public native void set(String name, int value) /*-{
        if (!this.atts) {
            this.atts = {};
        }
        this.atts[name] = value;
    }-*/;
    public native void set(String name, String value) /*-{
        if (!this.atts) {
            this.atts = {};
        }
        this.atts[name] = value;
    }-*/;
    public native void set(String name, Object value) /*-{
        if (!this.atts) {
            this.atts = {};
        }
        this.atts[name] = value;
    }-*/;
    public native boolean isset(String name) /*-{
        if (!this.atts) {
            return false;
        }
        return this.atts[name] != null;
    }-*/;
    public native boolean getFlag(String name) /*-{
        if (!this.atts) {
            return false;
        }
        if (this.atts[name] == null) {
            return false;
        }
        return this.atts[name];
    }-*/;
    public native int getInt(String name) throws JavaScriptException /*-{
        if (!this.atts) {
            throw(name + " is not set");
        }
        if (this.atts[name] == null) {
            throw(name + " is not set");
        }
        return this.atts[name];
    }-*/;
    public native String getString(String name) throws JavaScriptException /*-{
        if (!this.atts) {
            return null;
        }
        if (this.atts[name] == null) {
            return null;
        }
        return this.atts[name];
    }-*/;
    public native <X> X getObject(String name) throws JavaScriptException /*-{
        if (!this.atts) {
            return null;
        }
        if (this.atts[name] == null) {
            return null;
        }
        return this.atts[name];
    }-*/;
    protected Window() {
    }
}
