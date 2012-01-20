package org.atmosphere.samples.multirequest.jobs;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class Job1 extends AtmosphereJob {

	@Scheduled(cron = "0/3 * * * * ?")
	public void runAction() {
		String message = "Message from Job1";
		sendMessages("job1", message);
	}
}
