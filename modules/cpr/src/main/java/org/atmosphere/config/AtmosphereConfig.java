package org.atmosphere.config;

import java.util.List;

import com.thoughtworks.xstream.annotations.XStreamAlias;
import com.thoughtworks.xstream.annotations.XStreamImplicit;

@XStreamAlias("atmosphere-handlers")
public class AtmosphereConfig {
	
	@XStreamAlias(impl=AtmosphereHandler.class, value="atmosphere-handler")
	@XStreamImplicit(itemFieldName="atmosphere-handler")
	private List<AtmosphereHandler> atmosphereHandler;

	public List<AtmosphereHandler> getAtmosphereHandler() {
		return atmosphereHandler;
	}

	public void setAtmosphereHandler(List<AtmosphereHandler> atmosphereHandler) {
		this.atmosphereHandler = atmosphereHandler;
	}

}
