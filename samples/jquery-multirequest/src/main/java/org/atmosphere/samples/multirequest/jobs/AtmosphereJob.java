package org.atmosphere.samples.multirequest.jobs;

import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.BroadcasterFactory;
import org.atmosphere.jersey.JerseyBroadcaster;

public abstract class AtmosphereJob {

	protected void sendMessages(String topic, String message) {
		Broadcaster broadcaster = getBroadcaster(topic);
		if (broadcaster != null) {
			broadcaster.broadcast(message);
		}
	}

	protected Broadcaster getBroadcaster(String topic) {
		return BroadcasterFactory.getDefault().lookup(JerseyBroadcaster.class,
				topic);
	}
}
