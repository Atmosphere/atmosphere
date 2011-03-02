package org.timepedia.exporter.rebind;

import com.google.gwt.core.ext.typeinfo.JMethod;
import com.google.gwt.core.ext.typeinfo.JPrimitiveType;

import org.timepedia.exporter.client.SType;

/**
 * Represents a bean property or method which is a structural type field.
 */
public class JStructuralTypeField {

  private JExportableClassType exportableClassType;

  private JMethod setterMethod;

  public JStructuralTypeField(JExportableClassType exportableClassType,
      JMethod setterMethod) {

    this.exportableClassType = exportableClassType;
    this.setterMethod = setterMethod;
  }

  public String JavaDeclaration() {
    return setterMethod.getReturnType().getQualifiedSourceName() + " "
        + setterMethod.getName() + "("
        + setterMethod.getParameters()[0].getType().getQualifiedSourceName()
        + " arg)";
  }

  public boolean isVoidReturn() {
    return setterMethod.getReturnType().equals(JPrimitiveType.VOID);
  }

  public String getMethodName() {
    return setterMethod.getName();
  }

  public String getReturnType() {
    return setterMethod.getReturnType().getQualifiedSourceName();
  }

  public String getFieldValueCast() {
    return setterMethod.getParameters()[0].getType().isPrimitive() != null ?
    "(double)": "(Object)";
  }
  public String getFieldJSNIType() {
    return setterMethod.getParameters()[0].getType().isPrimitive() != null ? 
        "D" : "Ljava/lang/Object;";
  }

  public String getName() {
    SType st = setterMethod.getAnnotation(SType.class);
    if(st != null) {
      return st.value();
    }
    return beanize(setterMethod.getName());
  }

  private String beanize(String name) {
    String prop = name.startsWith("set") ? name.substring(3) : name;
    return Character.toLowerCase(prop.charAt(0))+prop.substring(1);
  }

  public String getFieldLowestType() {
    JPrimitiveType type = setterMethod.getParameters()[0].getType()
        .isPrimitive();
    return type != null ? type.getQualifiedSourceName() : "Object"; 
  }

  public String getFieldType() {
    return setterMethod.getParameters()[0].getType().getQualifiedSourceName();
  }

  public JExportableType getExportableType() {
    return exportableClassType.getExportableTypeOracle().findExportableType(getFieldType());
  }
}
