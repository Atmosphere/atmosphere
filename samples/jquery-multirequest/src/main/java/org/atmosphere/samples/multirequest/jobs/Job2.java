package org.atmosphere.samples.multirequest.jobs;

import org.springframework.stereotype.Component;

@Component
public class Job2 extends AtmosphereJob {

//	@Scheduled(cron = "0/5 * * * * ?")
	public void runAction() {
		String message = "Message from Job2";
		sendMessages("job2", message);
	}
}
