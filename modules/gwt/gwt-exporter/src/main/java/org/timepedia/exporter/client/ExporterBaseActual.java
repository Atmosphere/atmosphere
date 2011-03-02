package org.timepedia.exporter.client;

import com.google.gwt.core.client.GWT;
import com.google.gwt.core.client.JavaScriptObject;
import com.google.gwt.core.client.JsArray;
import com.google.gwt.core.client.JsArrayNumber;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Methods used to maintain a mapping between JS types and Java (GWT) objects.
 */
public class ExporterBaseActual extends ExporterBaseImpl {

  public static final String WRAPPER_PROPERTY = "__gwtex_wrap";

  private native static JavaScriptObject wrap0(Exportable type,
      JavaScriptObject constructor, String wrapProp) /*-{
           return new (constructor)(type);
      }-*/;

  private HashMap typeMap = new HashMap();

  private HashMap<Class, JavaScriptObject> dispatchMap
      = new HashMap<Class, JavaScriptObject>();

  private HashMap<Class, JavaScriptObject> staticDispatchMap
      = new HashMap<Class, JavaScriptObject>();

  //TODO: track garbage collected wrappers and remove mapping

  private IdentityHashMap<Object, JavaScriptObject> wrapperMap = null;

  public ExporterBaseActual() {
    if (!GWT.isScript()) {
      wrapperMap = new IdentityHashMap<Object, JavaScriptObject>();
    }
  }

  public void addTypeMap(Exportable type,
      JavaScriptObject exportedConstructor) {
    addTypeMap(type.getClass(), exportedConstructor);
  }

  public void addTypeMap(Class type, JavaScriptObject exportedConstructor) {
    typeMap.put(type, exportedConstructor);
  }

  public void setWrapper(Object instance, JavaScriptObject wrapper) {
    if (GWT.isScript()) {
      setWrapperJS(instance, wrapper, WRAPPER_PROPERTY);
    } else {
      setWrapperHosted(instance, wrapper);
    }
  }

  public JavaScriptObject typeConstructor(Exportable type) {
    return typeConstructor(type.getClass());
  }

  public JavaScriptObject typeConstructor(Class type) {
    Object o = typeMap.get(type);
    return (JavaScriptObject) o;
  }

  public JavaScriptObject wrap(Exportable type) {
    if (type == null) {
      return null;
    }

    if (!GWT.isScript()) {
      JavaScriptObject wrapper = wrapperMap.get(type);
      if (wrapper != null) {
        return wrapper;
      }
    } else {
      JavaScriptObject wrapper = getWrapperJS(type, WRAPPER_PROPERTY);
      if (wrapper != null) {
        return wrapper;
      }
    }
    JavaScriptObject wrapper = wrap0(type, typeConstructor(type),
        WRAPPER_PROPERTY);
    setWrapper(type, wrapper);
    return wrapper;
  }

  public JavaScriptObject wrap(Exportable[] type) {
    if (type == null) {
      return null;
    }

    JavaScriptObject wrapper = getWrapper(type);

    JsArray<JavaScriptObject> wrapperArray = wrapper.cast();
    for (int i = 0; i < type.length; i++) {
      wrapperArray.set(i, wrap(type[i]));
    }
    return wrapper;
  }

  private JavaScriptObject getWrapper(Object type) {
    JavaScriptObject wrapper = null;
    if (!GWT.isScript()) {
      wrapper = wrapperMap.get(type);
    } else {
      wrapper = getWrapperJS(type, WRAPPER_PROPERTY);
    }

    if (wrapper == null) {
      wrapper = JavaScriptObject.createArray();
      setWrapper(type, wrapper);
    }
    return wrapper;
  }

  private static native JavaScriptObject reinterpretCast(Object nl) /*-{
        return nl;
    }-*/;

  @Override
  public JavaScriptObject wrap(float[] type) {
    if (!GWT.isScript()) {
      if (type == null) {
        return null;
      }
      JavaScriptObject wrapper = getWrapper(type);
      JsArrayNumber wrapperArray = wrapper.cast();
      for (int i = 0; i < type.length; i++) {
        wrapperArray.set(i, type[i]);
      }
      return wrapper;
    } else {
      return reinterpretCast(type);
    }
  }

  @Override
  public JavaScriptObject wrap(byte[] type) {
    if (!GWT.isScript()) {
      if (type == null) {
        return null;
      }
      JavaScriptObject wrapper = getWrapper(type);
      JsArrayNumber wrapperArray = wrapper.cast();
      for (int i = 0; i < type.length; i++) {
        wrapperArray.set(i, type[i]);
      }
      return wrapper;
    } else {
      return reinterpretCast(type);
    }
  }

  @Override
  public JavaScriptObject wrap(char[] type) {
    if (!GWT.isScript()) {
      if (type == null) {
        return null;
      }
      JavaScriptObject wrapper = getWrapper(type);
      JsArrayNumber wrapperArray = wrapper.cast();
      for (int i = 0; i < type.length; i++) {
        wrapperArray.set(i, type[i]);
      }
      return wrapper;
    } else {
      return reinterpretCast(type);
    }
  }

  @Override
  public JavaScriptObject wrap(int[] type) {
    if (!GWT.isScript()) {
      if (type == null) {
        return null;
      }
      JavaScriptObject wrapper = getWrapper(type);
      JsArrayNumber wrapperArray = wrapper.cast();
      for (int i = 0; i < type.length; i++) {
        wrapperArray.set(i, type[i]);
      }
      return wrapper;
    } else {
      return reinterpretCast(type);
    }
  }

  @Override
  public JavaScriptObject wrap(long[] type) {
    if (!GWT.isScript()) {
      if (type == null) {
        return null;
      }
      JavaScriptObject wrapper = getWrapper(type);
      JsArrayNumber wrapperArray = wrapper.cast();
      for (int i = 0; i < type.length; i++) {
        wrapperArray.set(i, type[i]);
      }
      return wrapper;
    } else {
      return reinterpretCast(type);
    }
  }

  @Override
  public JavaScriptObject wrap(short[] type) {
    if (!GWT.isScript()) {
      if (type == null) {
        return null;
      }
      JavaScriptObject wrapper = getWrapper(type);
      JsArrayNumber wrapperArray = wrapper.cast();
      for (int i = 0; i < type.length; i++) {
        wrapperArray.set(i, type[i]);
      }
      return wrapper;
    } else {
      return reinterpretCast(type);
    }
  }

  public JavaScriptObject wrap(double[] type) {

    if (!GWT.isScript()) {
      if (type == null) {
        return null;
      }
      JavaScriptObject wrapper = getWrapper(type);
      JsArrayNumber wrapperArray = wrapper.cast();
      for (int i = 0; i < type.length; i++) {
        wrapperArray.set(i, type[i]);
      }
      return wrapper;
    } else {
      return reinterpretCast(type);
    }
  }

  private native JavaScriptObject getWrapperJS(Object type, String wrapProp) /*-{
    return type[wrapProp];
  }-*/;

  private void setWrapperHosted(Object instance, JavaScriptObject wrapper) {
    wrapperMap.put(instance, wrapper);
  }

  private native void setWrapperJS(Object instance, JavaScriptObject wrapper,
      String wrapperProperty) /*-{
    instance[wrapperProperty] = wrapper;
  }-*/;

  private native void declarePackage0(JavaScriptObject prefix, String pkg) /*-{
    prefix[pkg] || (prefix[pkg] = {});
  }-*/;

  @Override
  public void declarePackage(String packageName,
      String enclosingClassesString) {
    String superPackages[] = packageName.split("\\.");
    JavaScriptObject prefix = getWindow();
    for (int i = 0; i < superPackages.length; i++) {
      if (!superPackages[i].equals("client")) {
        declarePackage0(prefix, superPackages[i]);
        prefix = getProp(prefix, superPackages[i]);
      }
    }
    String enclosingClasses[] = enclosingClassesString.split("\\.");
    for (String enclosingName : enclosingClasses) {
      if (!enclosingName.trim().equals("")) {
        declarePackage0(prefix, enclosingName);
        prefix = getProp(prefix, enclosingName);
      }
    }
  }

  private static native JavaScriptObject getWindow() /*-{
    return $wnd;
  }-*/;

  private static native JavaScriptObject getProp(JavaScriptObject jso,
      String key) /*-{
    return jso[key];
  }-*/;

  @Override
  public JavaScriptObject getDispatch(Class clazz, String meth,
      JsArray<JavaScriptObject> arguments, boolean isStatic) {
    Map<Class, JavaScriptObject> dmap = isStatic ? staticDispatchMap
        : dispatchMap;
    JsArray<SignatureJSO> sigs = getSigs(dmap.get(clazz).cast(), meth,
        arguments.length());

    for (int i = 0; i < sigs.length(); i++) {
      SignatureJSO sig = sigs.get(i);
      if (sig.matches(arguments)) {
        JavaScriptObject javaFunc = sig.getFunction();
        if (!GWT.isScript()) {
          JavaScriptObject wrapFunc = sig.getWrapperFunc();
          return wrapFunc != null ? wrapFunction(wrapFunc, javaFunc) : javaFunc;
        } else {
          return javaFunc;
        }
      }
    }
    throw new RuntimeException(
        "Can't find exported method for given arguments");
  }

  // this is way more complicated than it needs to be, thanks to hosted mode
  private native static JavaScriptObject wrapFunction(JavaScriptObject wrapFunc,
      JavaScriptObject javaFunc) /*-{
     return function() {
         var i, newArgs = [];
         for(i = 0; i < arguments.length; i++) {
             newArgs[i] = arguments[i].__gwt_instance || arguments[i];
         }
         return wrapFunc.apply(null, [javaFunc.apply(this, newArgs)]);
     };
  }-*/;

  private native JsArray<SignatureJSO> getSigs(JavaScriptObject jsoMap,
      String meth, int arity) /*-{
    return jsoMap[meth][arity];
  }-*/;

  @Override
  public void registerDispatchMap(Class clazz, JavaScriptObject dispMap,
      boolean isStatic) {
    if (isStatic) {
      staticDispatchMap.put(clazz, dispMap);
    } else {
      dispatchMap.put(clazz, dispMap);
    }
  }

  final public static class SignatureJSO extends JavaScriptObject {

    protected SignatureJSO() {
    }

    public boolean matches(JsArray<JavaScriptObject> arguments) {
      // add argument matching logic
      // add structural type checks
      for (int i = 0; i < arguments.length(); i++) {
        Object jsType = getObject(i + 2);
        String argJsType = typeof(arguments, i);
        if (argJsType.equals("object")) {
          Object gwtObject = getJavaObject(arguments, i);
          if (gwtObject != null) {
            if (!gwtObject.getClass().equals(jsType)) {
              return false;
            }
          } else if (!jsType.equals("object")) {
            return false;
          }
        } else if (!jsType.equals(argJsType)) {
          return false;
        }
      }
      return true;
    }

    public native Object getJavaObject(JavaScriptObject args, int i) /*-{
      return args[i].__gwt_instance || null;
    }-*/;

    public native static String typeof(JavaScriptObject args, int i) /*-{
      return typeof(args[i]);
    }-*/;

    public native Object getObject(int i) /*-{
      return this[i];
    }-*/;

    public native JavaScriptObject getFunction() /*-{
      return this[0];
    }-*/;

    public native JavaScriptObject getWrapperFunc() /*-{
      return this[1];
    }-*/;
  }
}
