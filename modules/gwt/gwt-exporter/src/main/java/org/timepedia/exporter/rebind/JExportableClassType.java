package org.timepedia.exporter.rebind;

import com.google.gwt.core.ext.typeinfo.JClassType;
import com.google.gwt.core.ext.typeinfo.JConstructor;
import com.google.gwt.core.ext.typeinfo.JField;
import com.google.gwt.core.ext.typeinfo.JMethod;
import org.timepedia.exporter.client.ExportPackage;
import org.timepedia.exporter.client.ExporterUtil;
import org.timepedia.exporter.client.SType;

import java.util.ArrayList;

/**
 *
 */
public class JExportableClassType implements JExportable, JExportableType {

    private static final String IMPL_EXTENSION = "ExporterImpl";

    private ExportableTypeOracle exportableTypeOracle;

    private JClassType type;

    public JExportableClassType(ExportableTypeOracle exportableTypeOracle,
                                JClassType type) {
        this.exportableTypeOracle = exportableTypeOracle;

        this.type = type;
    }

    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        JExportableClassType that = (JExportableClassType) o;

        return getQualifiedSourceName().equals(that.getQualifiedSourceName());
    }

    public String[] getEnclosingClasses() {
        String[] enc = type.getName().split("\\.");
        String[] enclosingTypes = new String[enc.length - 1];
        if (enc.length > 1) {
            System.arraycopy(enc, 0, enclosingTypes, 0, enclosingTypes.length);
        }
        return enclosingTypes;
    }

    public JExportableConstructor[] getExportableConstructors() {
        ArrayList<JExportableConstructor> exportableCons
                = new ArrayList<JExportableConstructor>();

        if (isInstantiable()) {
            JClassType exportType = type;
            if (exportableTypeOracle.isExportOverlay(type)) {
                // export public no-arg constructor for overlay types
                exportType = exportableTypeOracle.getExportOverlayType(type);
            }
            for (JConstructor method : exportType.getConstructors()) {
                if (method.isConstructor() == null) {
                    continue;
                }

                if (exportableTypeOracle.isExportable(method)) {
                    exportableCons.add(new JExportableConstructor(this, method));
                }
            }
        }
        return exportableCons.toArray(new JExportableConstructor[0]);
    }

    public JExportableField[] getExportableFields() {
        ArrayList<JExportableField> exportableFields
                = new ArrayList<JExportableField>();

        for (JField field : type.getFields()) {
            if (exportableTypeOracle.isExportable(field)) {
                exportableFields.add(new JExportableField(this, field));
            }
        }
        return exportableFields.toArray(new JExportableField[0]);
    }

    public JExportableMethod[] getExportableMethods() {
        ArrayList<JExportableMethod> exportableMethods
                = new ArrayList<JExportableMethod>();

        for (JMethod method : type.getMethods()) {
            if (method.isConstructor() != null) {
                continue;
            }

            if (exportableTypeOracle.isExportable(method)) {
                exportableMethods.add(new JExportableMethod(this, method));
            }
        }
        return exportableMethods.toArray(new JExportableMethod[0]);
    }

    public JExportableClassType getExportableSuperClassType() {
        return exportableTypeOracle
                .findFirstExportableSuperClassType(type.getSuperclass());
    }

    public ExportableTypeOracle getExportableTypeOracle() {
        return exportableTypeOracle;
    }

    public String getExporterImplementationName() {
        return type.getSimpleSourceName() + IMPL_EXTENSION;
    }

    public String getHostedModeJsTypeCast() {
        return null;
    }

    public String getJsTypeOf() {
        return exportableTypeOracle.getJsTypeOf(getType());
    }

    public String getJSConstructor() {
        return getJSExportPackage() + "." + type.getName();
    }

    public String getJSExportPackage() {
        String requestedPackageName = getPrefix();
        ExportPackage ann = type.getAnnotation(ExportPackage.class);
        if (ann != null) {
            requestedPackageName = ann.value();
        } else if (type.getEnclosingType() != null) {
            JExportableClassType encType = exportableTypeOracle
                    .findExportableClassType(
                            type.getEnclosingType().getQualifiedSourceName());
            if (encType != null) {
                return encType.getJSExportPackage();
            }
        }
        return requestedPackageName;
    }

    public String getJSNIReference() {
        return type.getJNISignature();
    }

    public String getJSQualifiedExportName() {
        return getJSConstructor();
    }

    public String getPackageName() {
        return type.getPackage().getName();
    }

    public String getPrefix() {
        String prefix = "";
        boolean firstClientPackage = true;
        for (String pkg : type.getPackage().getName().split("\\.")) {
            if (firstClientPackage && pkg.equals("client")) {
                firstClientPackage = false;
                continue;
            }
            prefix += pkg;
            prefix += '.';
        }
        // remove trailing .
        return prefix.substring(0, prefix.length() - 1);
    }

    public String getQualifiedExporterImplementationName() {
        return getPackageName() + "." + getExporterImplementationName();
    }

    public String getQualifiedSourceName() {
        return getType().getQualifiedSourceName();
    }

    public JStructuralTypeField[] getStructuralTypeFields() {
        if (!isStructuralType()) {
            return new JStructuralTypeField[0];
        } else {
            ArrayList<JStructuralTypeField> fields
                    = new ArrayList<JStructuralTypeField>();
            for (JMethod method : type.getMethods()) {
                if (method.getName().startsWith("set")
                        && Character.isUpperCase(method.getName().charAt(3))
                        && method.getParameters().length == 1
                        || method.getAnnotation(SType.class) != null) {
                    fields.add(new JStructuralTypeField(this, method));
                }
            }
            return fields.toArray(new JStructuralTypeField[0]);
        }
    }

    public JClassType getType() {
        return type;
    }

    public JClassType getTypeToExport() {
        return type;
    }

    public String getWrapperFunc() {
        if (!needsExport()) {
            return null;
        }
        return "@" + ExporterUtil.class.getName()
                + "::wrap(Lorg/timepedia/exporter/client/Exportable;)";
    }

    public int hashCode() {
        return getQualifiedSourceName().hashCode();
    }

    public boolean isPrimitive() {
        return type.isPrimitive() != null;
    }

    public boolean isStructuralType() {
        return exportableTypeOracle.isStructuralType(this.getType());
    }

    public boolean isTransparentType() {
        return exportableTypeOracle.isJavaScriptObject(this)
                || exportableTypeOracle.isString(this) || exportableTypeOracle
                .isArray(this);
    }

    public boolean needsExport() {
        return !isPrimitive() && !isTransparentType();
    }

    public boolean isInstantiable() {
        return type.isInterface() == null && !type.isAbstract();
    }
}
