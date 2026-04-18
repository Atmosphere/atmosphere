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
package org.atmosphere.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;

/**
 * {@link StreamingSession} decorator that intercepts streamed LLM output,
 * extracts structured fields progressively via
 * {@link StructuredOutputParser#parseField}, and emits
 * {@link AiEvent.StructuredField}, {@link AiEvent.EntityStart}, and
 * {@link AiEvent.EntityComplete} events.
 *
 * <p>Follows the same wrapping pattern as {@link GuardrailCapturingSession}
 * and {@link MemoryCapturingSession}.</p>
 */
class StructuredOutputCapturingSession implements StreamingSession {

    private static final Logger logger = LoggerFactory.getLogger(
            StructuredOutputCapturingSession.class);
    private static final int PARSE_INTERVAL = 100;

    private final StreamingSession delegate;
    private final StructuredOutputParser parser;
    private final Class<?> responseType;
    private final StringBuilder accumulated = new StringBuilder();
    private final Set<String> emittedFields = new HashSet<>();
    private int lastParsedLength;
    private boolean entityStarted;

    StructuredOutputCapturingSession(StreamingSession delegate,
                                     StructuredOutputParser parser,
                                     Class<?> responseType) {
        this.delegate = delegate;
        this.parser = parser;
        this.responseType = responseType;
    }

    @Override
    public java.util.Map<Class<?>, Object> injectables() {
        return delegate.injectables();
    }

    @Override
    public String sessionId() {
        return delegate.sessionId();
    }

    @Override
    public void send(String text) {
        delegate.send(text);
        accumulated.append(text);
        attemptFieldParse();
    }

    @Override
    public void sendContent(Content content) {
        // Text variants feed the progressive field parser just like send().
        // Binary variants have no structured-output projection — forward
        // them to the delegate so the leaf session emits the binary frame
        // without poisoning the parser's accumulated JSON buffer.
        if (content instanceof Content.Text text) {
            send(text.text());
            return;
        }
        delegate.sendContent(content);
    }

    @Override
    public void emit(AiEvent event) {
        delegate.emit(event);
        if (event instanceof AiEvent.TextDelta delta) {
            accumulated.append(delta.text());
            attemptFieldParse();
        }
    }

    @Override
    public void sendMetadata(String key, Object value) {
        delegate.sendMetadata(key, value);
    }

    @Override
    public void progress(String message) {
        delegate.progress(message);
    }

    @Override
    public void complete() {
        emitEntity();
        delegate.complete();
    }

    @Override
    public void complete(String summary) {
        emitEntity();
        delegate.complete(summary);
    }

    @Override
    public void error(Throwable t) {
        delegate.error(t);
    }

    @Override
    public boolean isClosed() {
        return delegate.isClosed();
    }

    private void attemptFieldParse() {
        if (accumulated.length() - lastParsedLength < PARSE_INTERVAL) {
            return;
        }
        lastParsedLength = accumulated.length();

        try {
            var field = parser.parseField(accumulated.toString(), responseType);
            if (field.isPresent()) {
                var entry = field.get();
                if (emittedFields.add(entry.getKey())) {
                    if (!entityStarted) {
                        entityStarted = true;
                        delegate.emit(new AiEvent.EntityStart(
                                responseType.getSimpleName(),
                                parser.schemaInstructions(responseType)));
                    }
                    var schemaType = entry.getValue() != null
                            ? entry.getValue().getClass().getSimpleName().toLowerCase()
                            : "string";
                    delegate.emit(new AiEvent.StructuredField(
                            entry.getKey(), entry.getValue(), schemaType));
                }
            }
        } catch (Exception e) {
            logger.trace("Progressive field parse attempt failed", e);
        }
    }

    private void emitEntity() {
        if (accumulated.isEmpty()) {
            return;
        }
        try {
            if (!entityStarted) {
                delegate.emit(new AiEvent.EntityStart(
                        responseType.getSimpleName(),
                        parser.schemaInstructions(responseType)));
            }
            var entity = parser.parse(accumulated.toString(), responseType);
            delegate.emit(new AiEvent.EntityComplete(
                    responseType.getSimpleName(), entity));
        } catch (Exception e) {
            logger.debug("Structured output parsing failed for type {}",
                    responseType.getSimpleName(), e);
        }
    }
}
