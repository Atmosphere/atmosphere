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
package org.atmosphere.ai.sandbox;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test-only {@link SandboxProvider} registered via the test-scope
 * {@code META-INF/services} file. Records every {@link #create} so tests can
 * assert the annotation-to-limits mapping and the framework-owned lifecycle
 * (created before the tool body, closed exactly once on every terminal path)
 * without a Docker daemon. ServiceLoader instantiates its own copy, so all
 * observable state is static; call {@link #reset()} in {@code @BeforeEach}.
 */
public final class RecordingSandboxProvider implements SandboxProvider {

    static volatile boolean available = true;
    static volatile String lastImage;
    static volatile SandboxLimits lastLimits;
    static volatile Map<String, String> lastMetadata;
    static volatile RecordingSandbox lastSandbox;
    static final AtomicInteger CREATE_CALLS = new AtomicInteger();

    static void reset() {
        available = true;
        lastImage = null;
        lastLimits = null;
        lastMetadata = null;
        lastSandbox = null;
        CREATE_CALLS.set(0);
    }

    @Override
    public String name() {
        return "recording";
    }

    @Override
    public boolean isAvailable() {
        return available;
    }

    @Override
    public Sandbox create(String image, SandboxLimits limits, Map<String, String> metadata) {
        CREATE_CALLS.incrementAndGet();
        lastImage = image;
        lastLimits = limits;
        lastMetadata = metadata;
        var sandbox = new RecordingSandbox(limits,
                metadata == null ? Map.of() : Map.copyOf(metadata));
        lastSandbox = sandbox;
        return sandbox;
    }

    /** In-memory {@link Sandbox} that counts raw {@code close()} calls. */
    static final class RecordingSandbox implements Sandbox {

        private final SandboxLimits limits;
        private final Map<String, String> metadata;
        private final AtomicInteger closeCalls = new AtomicInteger();

        RecordingSandbox(SandboxLimits limits, Map<String, String> metadata) {
            this.limits = limits;
            this.metadata = metadata;
        }

        int closeCalls() {
            return closeCalls.get();
        }

        boolean closed() {
            return closeCalls.get() > 0;
        }

        @Override
        public String id() {
            return "recording-sandbox";
        }

        @Override
        public SandboxExec exec(List<String> command, Duration timeout) {
            return new SandboxExec(0, "", "", Duration.ZERO, false);
        }

        @Override
        public void writeFile(Path pathInsideSandbox, String content) {
        }

        @Override
        public String readFile(Path pathInsideSandbox) {
            throw new IllegalStateException("no file: " + pathInsideSandbox);
        }

        @Override
        public SandboxLimits limits() {
            return limits;
        }

        @Override
        public void close() {
            // Raw counter, deliberately not idempotent-guarded: the tests
            // assert the CALLER (the binding's scope) closes exactly once.
            closeCalls.incrementAndGet();
        }

        @Override
        public Map<String, String> metadata() {
            return metadata;
        }
    }
}
