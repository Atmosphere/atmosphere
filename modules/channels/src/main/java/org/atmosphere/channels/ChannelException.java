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
package org.atmosphere.channels;

/**
 * Exception thrown by messaging channel operations.
 */
public class ChannelException extends RuntimeException {

    private final ChannelType channelType;
    private final boolean retryable;

    public ChannelException(ChannelType channelType, String message) {
        this(channelType, message, false);
    }

    public ChannelException(ChannelType channelType, String message, boolean retryable) {
        super("[" + channelType.id() + "] " + message);
        this.channelType = channelType;
        this.retryable = retryable;
    }

    public ChannelException(ChannelType channelType, String message, Throwable cause, boolean retryable) {
        super("[" + channelType.id() + "] " + message, cause);
        this.channelType = channelType;
        this.retryable = retryable;
    }

    public ChannelType channelType() {
        return channelType;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
