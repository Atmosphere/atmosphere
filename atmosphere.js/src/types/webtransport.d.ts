/*
 * Copyright 2011-2026 Async-IO.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * Minimal WebTransport API type declarations.
 *
 * The WebTransport API is not yet included in lib.dom.d.ts, so we
 * declare just enough of the surface area that the transport needs.
 * These declarations can be removed once TypeScript ships built-in
 * WebTransport types.
 *
 * @see https://www.w3.org/TR/webtransport/
 */

interface WebTransportCloseInfo {
  closeCode?: number;
  reason?: string;
}

interface WebTransportBidirectionalStream {
  readonly readable: ReadableStream<Uint8Array>;
  readonly writable: WritableStream<Uint8Array>;
}

interface WebTransportHash {
  algorithm: string;
  value: BufferSource;
}

interface WebTransportOptions {
  allowPooling?: boolean;
  congestionControl?: string;
  requireUnreliable?: boolean;
  serverCertificateHashes?: WebTransportHash[];
}

interface WebTransport {
  readonly ready: Promise<void>;
  readonly closed: Promise<WebTransportCloseInfo>;
  readonly datagrams: {
    readonly readable: ReadableStream<Uint8Array>;
    readonly writable: WritableStream<Uint8Array>;
  };
  readonly incomingBidirectionalStreams: ReadableStream<WebTransportBidirectionalStream>;
  close(closeInfo?: WebTransportCloseInfo): void;
  createBidirectionalStream(): Promise<WebTransportBidirectionalStream>;
}

declare var WebTransport: {
  prototype: WebTransport;
  new (url: string, options?: WebTransportOptions): WebTransport;
};
