/*
 * Copyright 2008-2020 Async-IO.org
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

package org.atmosphere.util;

import org.atmosphere.cpr.BroadcastFilter;
import org.atmosphere.cpr.BroadcastFilter.BroadcastAction.ACTION;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Simple Aggregator that aggregate broadcasted String until it reach the
 * limit.
 *
 * @author Jeanfrancois Arcand
 */
public class StringFilterAggregator implements BroadcastFilter {

    private final int maxBufferedString;

    private final AtomicReference<StringBuilder> bufferedMessage = new AtomicReference<StringBuilder>(new StringBuilder());

    public StringFilterAggregator() {
        maxBufferedString = 256;
    }

    public StringFilterAggregator(int maxBufferedString) {
        this.maxBufferedString = maxBufferedString;
    }

    public BroadcastAction filter(String broadcasterId, Object originalMessage, Object message) {
        if (message instanceof String) {
            bufferedMessage.get().append(message);
            if (bufferedMessage.get().length() < maxBufferedString) {
                return new BroadcastAction(ACTION.ABORT, message);
            } else {
                message = bufferedMessage.toString();
                bufferedMessage.get().delete(0, bufferedMessage.get().length());
                return new BroadcastAction(ACTION.CONTINUE, message);
            }
        } else {
            return new BroadcastAction(message);
        }
    }

}
