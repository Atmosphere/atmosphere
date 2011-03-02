package org.timepedia.exporter.rebind;

/**
 * Interface for all exportable elements. Maps Java package, field, and method
 * names into JS equivalents
 *
 * @author Ray Cromwell <ray@timepedia.org>
 */
public interface JExportable {

  public String getJSQualifiedExportName();

  public String getJSNIReference();
}
