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

/**
 * No-op adapter for {@link WebTransportHandler}. Extend this class and
 * override only the callbacks you need.
 *
 * @author Jeanfrancois Arcand
 */
public class WebTransportHandlerAdapter implements WebTransportHandler {

    @Override
    public void onByteMessage(WebTransportSession session, byte[] data, int offset, int length) {
    }

    @Override
    public void onTextMessage(WebTransportSession session, String data) {
    }

    @Override
    public void onOpen(WebTransportSession session) {
    }

    @Override
    public void onClose(WebTransportSession session) {
    }

    @Override
    public void onError(WebTransportSession session, WebTransportProcessor.WebTransportException t) {
    }
}
