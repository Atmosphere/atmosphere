/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package org.atmosphere.gwt.js;

import org.timepedia.exporter.client.Export;
import org.timepedia.exporter.client.ExportClosure;
import org.timepedia.exporter.client.ExportPackage;
import org.timepedia.exporter.client.Exportable;

/**
 *
 * @author p.havelaar
 */
@Export
@ExportPackage("atmosphere")
@ExportClosure
public interface OnBeforeDisconnected extends Exportable {
    public void execute();
}
