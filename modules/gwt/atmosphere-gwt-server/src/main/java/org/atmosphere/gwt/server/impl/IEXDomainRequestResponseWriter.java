/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package org.atmosphere.gwt.server.impl;

import com.google.gwt.rpc.server.ClientOracle;
import com.google.gwt.user.server.rpc.SerializationPolicy;

/**
 *
 * @author p.havelaar
 */
public class IEXDomainRequestResponseWriter extends StreamingProtocolResponseWriter {

    public IEXDomainRequestResponseWriter(GwtAtmosphereResourceImpl resource, SerializationPolicy serializationPolicy, ClientOracle clientOracle) {
        super(resource, serializationPolicy, clientOracle);
    }
    
	@Override
	protected int getPaddingRequired() {
		return 2048;
    }
    
    @Override
    String getContentType() {
        return "application/comet";
    }
}
