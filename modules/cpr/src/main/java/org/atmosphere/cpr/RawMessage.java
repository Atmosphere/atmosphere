/*
 * Copyright 2008-2026 Async-IO.org
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

import java.io.Serializable;

/**
 * A wrapper that marks a message as already-encoded ("raw"), signaling the
 * framework to deliver it as-is without running it through {@code @Message}
 * decoders and encoders in {@link AtmosphereHandler#onStateChange}.
 * <p>
 * Use this when broadcasting pre-encoded content (e.g. Room protocol
 * messages, pre-serialized JSON) that must not be re-processed by the
 * handler's message pipeline.
 *
 * @since 4.0.3
 */
public class RawMessage implements Serializable {
    private static final long serialVersionUID = 1L;

    private final Object message;

    public RawMessage(Object message) {
        this.message = message;
    }

    /**
     * @return the wrapped raw message
     */
    public Object message() {
        return message;
    }

    @Override
    public String toString() {
        return message.toString();
    }
}
