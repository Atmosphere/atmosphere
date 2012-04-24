package org.timepedia.exporter.rebind;

import com.google.gwt.core.ext.Generator;
import com.google.gwt.core.ext.GeneratorContext;
import com.google.gwt.core.ext.TreeLogger;
import com.google.gwt.core.ext.UnableToCompleteException;

public class ExporterGenerator extends Generator {

    public String generate(TreeLogger logger, GeneratorContext ctx,
                           String requestedClass) throws UnableToCompleteException {
        ClassExporter classExporter = new ClassExporter(logger, ctx);
        String generatedClass = classExporter.exportClass(requestedClass, true);
        return generatedClass;
    }
}
