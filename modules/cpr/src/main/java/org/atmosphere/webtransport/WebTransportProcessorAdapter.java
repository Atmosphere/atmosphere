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
package org.atmosphere.webtransport;

import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResponse;

/**
 * No-op adapter for {@link WebTransportProcessor}. Used as the default when
 * no container-specific implementation is available.
 *
 * @author Jeanfrancois Arcand
 */
public class WebTransportProcessorAdapter implements WebTransportProcessor {

    @Override
    public WebTransportProcessor configure(AtmosphereConfig config) {
        return this;
    }

    @Override
    public WebTransportProcessor registerWebTransportHandler(String path, WebTransportHandlerProxy handler) {
        return this;
    }

    @Override
    public void open(WebTransportSession session, AtmosphereRequest request, AtmosphereResponse response) {
    }

    @Override
    public void invokeWebTransportProtocol(WebTransportSession session, String message) {
    }

    @Override
    public void invokeWebTransportProtocol(WebTransportSession session, byte[] data, int offset, int length) {
    }

    @Override
    public void close(WebTransportSession session, int closeCode) {
    }

    @Override
    public void notifyListener(WebTransportSession session, WebTransportEventListener.WebTransportEvent<?> event) {
    }

    @Override
    public void destroy() {
    }
}
