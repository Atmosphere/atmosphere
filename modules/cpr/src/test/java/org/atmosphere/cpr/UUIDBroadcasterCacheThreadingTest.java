package org.atmosphere.cpr;

import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.DefaultBroadcaster;
import org.atmosphere.cpr.DefaultBroadcasterFactory;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class UUIDBroadcasterCacheThreadingTest {

	public static final java.util.UUID UUID = java.util.UUID.randomUUID();
	private static final String BROADCASTER_ID = "B1";
	public static final int NUM_MESSAGES = 100000;
	private AtomicInteger counter = new AtomicInteger(0);
	private static final String CLIENT_ID = UUID.randomUUID().toString();
	private final List<Object> retreivedMessages = new ArrayList<>(NUM_MESSAGES);

	@Test
	public void testUuidBroadcasterCacheThreading() {
		AtmosphereConfig config = new AtmosphereFramework().getAtmosphereConfig();
		DefaultBroadcasterFactory factory = new DefaultBroadcasterFactory(DefaultBroadcaster.class, "NEVER", config);
		config.framework().setBroadcasterFactory(factory);
		UUIDBroadcasterCache cache = new UUIDBroadcasterCache();
		cache.configure(config);

		Thread t = new Thread(() -> {
			for (int i = 0; i < NUM_MESSAGES; i++) {
				BroadcastMessage broadcastMessage = createBroadcastMessage();
				cache.addToCache(BROADCASTER_ID, CLIENT_ID, broadcastMessage);
				System.out.println("ADDED: " + broadcastMessage.message());
			}
		});
		t.start();

		long endTime = System.currentTimeMillis() + 10000;
		int totalRetrieved = 0;
		while (totalRetrieved < NUM_MESSAGES && System.currentTimeMillis() < endTime) {
			List<Object> messages = cache.retrieveFromCache(BROADCASTER_ID, CLIENT_ID);
			if (!messages.isEmpty()) {
				retreivedMessages.addAll(messages);
				totalRetrieved += messages.size();
				System.out.println("Total received " + totalRetrieved);
			}
		}
		Assert.assertTrue("Did not receive all the messages", NUM_MESSAGES == totalRetrieved);
	}

	private BroadcastMessage createBroadcastMessage() {
		counter.addAndGet(1);
		return new BroadcastMessage("" + counter, counter);
	}

}
