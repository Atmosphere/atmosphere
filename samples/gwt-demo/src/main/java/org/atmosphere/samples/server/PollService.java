/*
 * Copyright 2013 Jeanfrancois Arcand
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.atmosphere.samples.server;

import org.atmosphere.gwt.poll.AtmospherePollService;
import org.atmosphere.samples.client.Event;
import org.atmosphere.samples.client.Poll;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Timer;
import java.util.TimerTask;

/**
 * @author p.havelaar
 */
public class PollService extends AtmospherePollService
        implements Poll {

    @Override
    public Event pollDelayed(final int milli) {

        final SuspendInfo info = suspend();

        Timer t = new Timer();
        t.schedule(new TimerTask() {
            @Override
            public void run() {
                try {
                    info.writeAndResume(new Event(milli, "Polling: Delayed event"));
                } catch (IOException e) {
                    logger.error("Failed to write and resume", e);
                }
            }
        }, milli);

        return null;
    }

    private Logger logger = LoggerFactory.getLogger(PollService.class.getName());
}
