package org.timepedia.exporter.rebind;

import com.google.gwt.core.ext.typeinfo.JAbstractMethod;
import com.google.gwt.core.ext.typeinfo.JParameter;
import com.google.gwt.core.ext.typeinfo.JType;

/**
 * Wrapper utility class for dealing with exportable constructors
 *
 * @author Ray Cromwell (ray@timepedia.org)
 */
public class JExportableConstructor extends JExportableMethod {

    private static final String STATIC_FACTORY_NAME = "___create";

    public JExportableConstructor(JExportableClassType exportableEnclosingType,
                                  JAbstractMethod method) {
        super(exportableEnclosingType, method);
    }

    private void assertExportable(JType param) {

    }

    public String getJSNIReference() {
        String reference = exportableEnclosingType.getQualifiedSourceName() + "::"
                + getStaticFactoryMethodName() + "(";
        JParameter[] params = method.getParameters();
        for (int i = 0; i < params.length; i++) {
            reference += params[i].getType().getJNISignature();
        }
        reference += ")";
        return reference;
    }

    public String getStaticFactoryMethodName() {
        return STATIC_FACTORY_NAME;
    }

    public JExportableType getExportableReturnType() {
        return exportableEnclosingType;
    }

    public String getStaticFactoryJSNIReference() {
        String reference =
                exportableEnclosingType.getQualifiedExporterImplementationName() + "::"
                        + getStaticFactoryMethodName() + "(";
        JParameter[] params = method.getParameters();
        for (int i = 0; i < params.length; i++) {
            reference += params[i].getType().getJNISignature();
        }
        reference += ")";
        return reference;
    }

    public String getJSQualifiedExportName() {
        return exportableEnclosingType.getJSQualifiedExportName();
    }
}
