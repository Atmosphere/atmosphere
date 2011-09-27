package org.timepedia.exporter.rebind;

import com.google.gwt.core.ext.typeinfo.JArrayType;
import com.google.gwt.core.ext.typeinfo.JType;
import org.timepedia.exporter.client.ExporterUtil;

/**
 *
 */
public class JExportableArrayType extends JExportableClassType
        implements JExportableType {

    public JExportableArrayType(ExportableTypeOracle exportableTypeOracle,
                                JArrayType array) {
        super(exportableTypeOracle, array);
        this.exportableTypeOracle = exportableTypeOracle;
        this.array = array;
    }

    private ExportableTypeOracle exportableTypeOracle;

    private JArrayType array;

    public boolean needsExport() {
        return true;
    }

    public String getQualifiedSourceName() {
        return array.getQualifiedSourceName();
    }

    @Override
    public String getWrapperFunc() {
        JType type = array.getComponentType();
        return "@" + ExporterUtil.class.getName() + "::wrap(["
                + type.getJNISignature() + ")";
    }

    public JExportableType getComponentType() {
        return exportableTypeOracle
                .findExportableType(array.getComponentType().getQualifiedSourceName());
    }
}
