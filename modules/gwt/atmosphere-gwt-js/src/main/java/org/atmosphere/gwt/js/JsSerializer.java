/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package org.atmosphere.gwt.js;

import org.atmosphere.gwt.client.AtmosphereGWTSerializer;
import org.atmosphere.gwt.client.SerialMode;
import org.atmosphere.gwt.client.SerialTypes;

/**
 *
 * @author p.havelaar
 */
@SerialTypes(value=String.class, mode=SerialMode.PLAIN, pushmode=SerialMode.PLAIN)
abstract public class JsSerializer extends AtmosphereGWTSerializer {

}
