package org.atmosphere.cpr;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class DefaultAtmosphereResourceSessionFactory extends AtmosphereResourceSessionFactory {
	private final ConcurrentMap<String, AtmosphereResourceSession> sessions = new ConcurrentHashMap<String, AtmosphereResourceSession>();
	
	final AtmosphereResourceEventListener disconnectListener = new AtmosphereResourceEventListenerAdapter() {
		public void onDisconnect(AtmosphereResourceEvent event) {
			String uuid = event.getResource().uuid();
			AtmosphereResourceSession session = sessions.remove(uuid);
			if (session != null) {
				session.destroy();
			}
		}
		
		public String toString() {
			return "DefaultAtmosphereResourceSessionFactory.DISCONNECT_LISTENER";
		};
	};
	
	@Override
	public AtmosphereResourceSession getSession(AtmosphereResource r, boolean create) {
		AtmosphereResourceSession session = sessions.get(r.uuid());
		if (create && session == null) {
			r.addEventListener(disconnectListener);
			session = new DefaultAtmosphereResourceSession();
			
			AtmosphereResourceSession existing = sessions.putIfAbsent(r.uuid(), session);
			
			if (existing != null) {
				session = existing;
			}
		}
		return session;
	}
	
	protected AtmosphereResourceEventListener getDisconnectListener() {
		return disconnectListener;
	}
}
