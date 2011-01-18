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
