package org.timepedia.exporter.rebind;

/**
 * Represents types that might be exported.
 */
public interface JExportableType {

  /**
   * True if this type needs export to work in Javascript.
   */
  boolean needsExport();
  /*
   * The Java qualified name of this type.
   */
  String getQualifiedSourceName();

  /**
   * The name of a JS type cast operation that may be required to debox
   * Java values for Javascript in hosted mode.
   */
  String getHostedModeJsTypeCast();

  String getJsTypeOf();
  
  String getWrapperFunc();
}
