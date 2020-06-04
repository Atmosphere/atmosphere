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


package org.atmosphere.cpr;

/**
 * Transform a message before it get broadcasted to {@link AtmosphereHandler#onStateChange(org.atmosphere.cpr.AtmosphereResourceEvent) }.
 * <p/>
 * See {@link org.atmosphere.util.XSSHtmlFilter} for an example.
 *
 * @author Jeanfrancois Arcand
 */
public interface BroadcastFilter {

    /**
     * When a message is about to get cached and some {@link BroadcastFilter} are defined, and when no {@link AtmosphereResource}
     * is available, a no-op {@link AtmosphereResource} with uuid == -1 will be used to invoke BroadcastFilter.
     */
    String VOID_ATMOSPHERE_RESOURCE_UUID = "-1";

    /**
     * Simple class that tells the {@link Broadcaster} to broadcast or not the transformed value.
     */
    class BroadcastAction {

        private final ACTION a;
        private final Object o;
        private Object originalMsg;

        public enum ACTION {
            /**
             * Return CONTINUE to invoke all remaining {@link BroadcastFilter}s.
             */
            CONTINUE,
            /**
             * Return ABORT to stop invoking any remaining {@link BroadcastFilter} and to discard the message
             * for being delivered to {@link AtmosphereHandler#onStateChange(AtmosphereResourceEvent)}.
             */
            ABORT,
            /**
             * Return SKIP to stop invoking any remaining {@link BroadcastFilter}, but deliver the last transformed message.
             */
            SKIP
        }

        public BroadcastAction(ACTION a, Object o) {
            this.a = a;
            this.o = o;
        }

        public BroadcastAction(Object o) {
            this.a = ACTION.CONTINUE;
            this.o = o;
        }

        public Object message() {
            return o;
        }

        public ACTION action() {
            return a;
        }

        public Object originalMessage() {
            return originalMsg;
        }

        void setOriginalMsg(Object originalMsg) {
            this.originalMsg = originalMsg;
        }
    }

    /**
     * Transform or filter a message. Return BroadcastAction(ACTION.ABORT, message)
     * {@link Broadcaster} to discard the message, eg. to not broadcast it.
     *
     * @param broadcasterId the {@link org.atmosphere.cpr.Broadcaster#getID()} calling this object
     * @param originalMessage The original message which was {@link Broadcaster#broadcast(Object)};
     * @param message         The transformed or not message.
     * @return a transformed message.
     */
    BroadcastAction filter(String broadcasterId, Object originalMessage, Object message);

}


