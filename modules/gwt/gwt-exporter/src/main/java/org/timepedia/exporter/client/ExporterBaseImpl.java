package org.timepedia.exporter.client;

import com.google.gwt.core.client.JavaScriptObject;
import com.google.gwt.core.client.JsArray;

/**
 * No-op implementation when export is disabled.
 */
public class ExporterBaseImpl {

    public void addTypeMap(Exportable type,
                           JavaScriptObject exportedConstructor) {
    }

    public void addTypeMap(Class type, JavaScriptObject exportedConstructor) {
    }

    public void setWrapper(Object instance, JavaScriptObject wrapper) {
    }

    public JavaScriptObject typeConstructor(Exportable type) {
        return null;
    }

    public JavaScriptObject typeConstructor(String type) {
        return null;
    }

    public JavaScriptObject wrap(Exportable type) {
        return null;
    }

    public JavaScriptObject wrap(Exportable[] type) {
        return null;
    }

    public JavaScriptObject wrap(double[] type) {
        return null;
    }

    public JavaScriptObject wrap(float[] type) {
        return null;
    }

    public JavaScriptObject wrap(int[] type) {
        return null;
    }

    public JavaScriptObject wrap(char[] type) {
        return null;
    }

    public JavaScriptObject wrap(byte[] type) {
        return null;
    }

    public JavaScriptObject wrap(long[] type) {
        return null;
    }

    public JavaScriptObject wrap(short[] type) {
        return null;
    }

    public void declarePackage(String packageName, String enclosingClasses) {
    }

    public JavaScriptObject getDispatch(Class clazz, String meth,
                                        JsArray<JavaScriptObject> arguments, boolean isStatic) {
        return null;
    }

    public void registerDispatchMap(Class clazz, JavaScriptObject dispMap, boolean isStatic) {
    }
}

