/*
* Copyright 2011 Jeanfrancois Arcand
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
package org.atmosphere.gwt.client.extra;

import com.google.gwt.core.client.JavaScriptException;
import com.google.gwt.core.client.JavaScriptObject;

/**
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
     * @param url      the URL that the new window will display
     * @param name     the name of the window (e.g. "_blank")
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
