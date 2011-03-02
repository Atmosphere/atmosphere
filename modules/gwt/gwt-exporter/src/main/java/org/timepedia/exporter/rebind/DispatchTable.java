package org.timepedia.exporter.rebind;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Information to assist quick overloaded type resolution at runtime.
 */
public class DispatchTable {

  private boolean isOverloaded;

  private JExportableMethod method;

  /**
   * Add a signature to the dispatch table. Returns false if the same signature
   * occurs more than once.
   */
  public boolean addSignature(JExportableMethod method,
      JExportableParameter[] exportableParameters) {
    Set<Signature> sigs = sigMap.get(exportableParameters.length);
    if (sigs == null) {
      sigs = new HashSet<Signature>();
      sigMap.put(exportableParameters.length, sigs);
    }
    isOverloaded = sigMap.size() > 1 || isOverloaded;

    Signature sig = new Signature(method, exportableParameters);
    if (sigs.contains(sig)) {
      return false;
    } else {
      sigs.add(sig);
    }
    isOverloaded = sigs.size() > 1 || isOverloaded;
    return true;
  }

  public int maxArity() {
    return Collections.max(sigMap.keySet()).intValue();
  }

  public boolean isOverloaded() {
    return isOverloaded;
  }

  public static String toJSON(HashMap<String, DispatchTable> dispatchMap) {
    StringBuilder sb = new StringBuilder();
    sb.append("{");
    for (Map.Entry<String, DispatchTable> e : dispatchMap.entrySet()) {
      if (!e.getValue().isOverloaded()) {
        continue;
      }
      sb.append("\"" + e.getKey() + "\":" + e.getValue().toJSON() + ",");
    }
    sb.append("}");
    return sb.toString();
  }

  public static class Signature {

    private JExportableMethod method;

    private JExportableParameter[] exportableParameters;

    public Signature(JExportableMethod method,
        JExportableParameter[] exportableParameters) {
      this.method = method;
      this.exportableParameters = exportableParameters;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }

      Signature signature = (Signature) o;

      if (!Arrays
          .equals(exportableParameters, signature.exportableParameters)) {
        return false;
      }

      return true;
    }

    @Override
    public int hashCode() {
      return exportableParameters != null ? Arrays
          .hashCode(exportableParameters) : 0;
    }

    public String toJSON() {
      StringBuilder sb = new StringBuilder();
      sb.append("[");
      String functionRef = "@" + method.getJSNIReference();
      if (method.isStatic()) {
        sb.append(functionRef);
      } else {
        sb.append("function() { return this." + functionRef+".apply(this, arguments); }");
      }
      sb.append(",");
      
      sb.append(method.getExportableReturnType().getWrapperFunc()+",");
      for (JExportableParameter param : exportableParameters) {
        String jsType = param.getJsTypeOf();
        if (jsType.equals("number") || jsType.equals("object") ||
            jsType.equals("string") || jsType.equals("boolean")) {
          jsType = "\""+jsType+"\"";
        }
        sb.append(jsType + ",");
      }
      sb.append("]");
      return sb.toString();
    }
  }

  public String toJSON() {
    StringBuilder json = new StringBuilder();
    json.append("{");
    for (Integer arity : sigMap.keySet()) {
      json.append("" + arity + ":" + toJSON(sigMap.get(arity)) + ",");
    }
    json.append("}");
    return json.toString();
  }

  static boolean isAnyOverridden(HashMap<String, DispatchTable> dispatchMap) {
    for (Map.Entry<String, DispatchTable> e : dispatchMap.entrySet()) {
      if (e.getValue().isOverloaded()) {
        return true;
      }
    }
    return false;
  }
  private String toJSON(Set<Signature> signatures) {
    StringBuilder sb = new StringBuilder();
    sb.append("[");
    for (Signature s : signatures) {
      sb.append(s.toJSON() + ",");
    }
    sb.append("]");
    return sb.toString();
  }

  private Map<Integer, Set<Signature>> sigMap
      = new HashMap<Integer, Set<Signature>>();
}
