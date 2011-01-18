package org.atmosphere.samples.client;

import org.atmosphere.gwt.client.AtmosphereGWTSerializer;
import org.atmosphere.gwt.client.SerialTypes;

@SerialTypes(value = {Event.class})
public abstract class EventSerializer extends AtmosphereGWTSerializer {
}
