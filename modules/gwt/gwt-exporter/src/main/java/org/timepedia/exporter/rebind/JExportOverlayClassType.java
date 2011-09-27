package org.timepedia.exporter.rebind;

import com.google.gwt.core.ext.typeinfo.JClassType;

/**
 *
 */
public class JExportOverlayClassType extends JExportableClassType {

    private JClassType exportType;

    public JExportOverlayClassType(ExportableTypeOracle exportableTypeOracle,
                                   JClassType requestedType) {
        super(exportableTypeOracle, requestedType);
        exportType = exportableTypeOracle.getExportOverlayType(requestedType);
    }

    @Override
    public JClassType getType() {
        return exportType;
    }
}
