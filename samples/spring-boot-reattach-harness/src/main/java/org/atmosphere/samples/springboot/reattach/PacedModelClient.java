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
package org.atmosphere.samples.springboot.reattach;

import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.llm.ChatCompletionRequest;
import org.atmosphere.ai.llm.LlmClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * The harness's model: an in-process {@link LlmClient} that answers every
 * prompt with the same {@value #TOKENS_PER_REPLY} numbered tokens, one every
 * {@value #TOKEN_INTERVAL_MS}ms, and honours a cancel the way a remote model
 * call does. {@code BuiltInAgentRuntime} drives it through its cancellable
 * path, so a {@code session.stream(...)} turn that a disconnect cancels stops
 * here, and the turn ends with the {@code cancelled} frame.
 *
 * <p>Each call is recorded under the prompt it answered, so a test asks the
 * server ({@link ModelCallController}) whether a model call ran to its end or
 * was cancelled, rather than inferring it from what one client received. The
 * record keeps the last {@value #MAX_RECORDED_CALLS} calls (Correctness
 * Invariant #3).</p>
 */
public final class PacedModelClient implements LlmClient {

    private static final Logger logger = LoggerFactory.getLogger(PacedModelClient.class);

    /** Model name the harness reports. */
    public static final String MODEL = "harness-paced-model";

    /** Tokens per reply. */
    public static final int TOKENS_PER_REPLY = 12;

    /** Cadence of the tokens: a reply takes {@value #TOKENS_PER_REPLY} × this long. */
    public static final long TOKEN_INTERVAL_MS = 500L;

    /** Calls the record keeps; the oldest is dropped past this. */
    static final int MAX_RECORDED_CALLS = 256;

    /** How a model call ended, or that it has not yet. */
    public enum State { RUNNING, COMPLETED, CANCELLED }

    /**
     * One model call.
     *
     * @param prompt the prompt it answered
     * @param state  how it ended, or {@link State#RUNNING}
     * @param tokens tokens streamed so far
     */
    public record ModelCall(String prompt, State state, int tokens) {
    }

    private final Map<String, ModelCall> calls = new LinkedHashMap<>(16, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, ModelCall> eldest) {
            return size() > MAX_RECORDED_CALLS;
        }
    };

    /** The tokens of every reply, in order: {@code "t00 "} to {@code "t11 "}. */
    public static List<String> replyTokens() {
        var tokens = new ArrayList<String>(TOKENS_PER_REPLY);
        for (int i = 0; i < TOKENS_PER_REPLY; i++) {
            tokens.add(String.format("t%02d ", i));
        }
        return List.copyOf(tokens);
    }

    /** The model call that answered {@code prompt} last, if it is still recorded. */
    public Optional<ModelCall> call(String prompt) {
        synchronized (calls) {
            return Optional.ofNullable(calls.get(prompt));
        }
    }

    @Override
    public void streamChatCompletion(ChatCompletionRequest request, StreamingSession session) {
        streamChatCompletion(request, session, new AtomicBoolean(), closeable -> { });
    }

    @Override
    public void streamChatCompletion(ChatCompletionRequest request, StreamingSession session,
                                     AtomicBoolean cancelled, Consumer<Closeable> streamSink) {
        var prompt = request.messages().isEmpty() ? "" : request.messages().getLast().content();
        // The runtime cancels the call by closing the stream it was handed, as it
        // closes the HTTP response of a remote model; the flag is its fallback.
        var closed = new AtomicBoolean();
        if (streamSink != null) {
            streamSink.accept(() -> closed.set(true));
        }
        record(prompt, State.RUNNING, 0);
        session.sendMetadata("model", MODEL);
        var tokens = replyTokens();
        int sent = 0;
        for (var token : tokens) {
            if (sleepUnlessCancelled(cancelled, closed)) {
                record(prompt, State.CANCELLED, sent);
                logger.info("Model call for '{}' cancelled after {}/{} token(s)", prompt, sent, tokens.size());
                return;
            }
            session.send(token);
            record(prompt, State.RUNNING, ++sent);
        }
        record(prompt, State.COMPLETED, sent);
        logger.info("Model call for '{}' completed: {} token(s)", prompt, sent);
        session.complete();
    }

    /** Wait one token interval; {@code true} when the call was cancelled meanwhile. */
    private static boolean sleepUnlessCancelled(AtomicBoolean cancelled, AtomicBoolean closed) {
        if (isCancelled(cancelled, closed)) {
            return true;
        }
        try {
            Thread.sleep(TOKEN_INTERVAL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return true;
        }
        return isCancelled(cancelled, closed);
    }

    private static boolean isCancelled(AtomicBoolean cancelled, AtomicBoolean closed) {
        return cancelled.get() || closed.get() || Thread.currentThread().isInterrupted();
    }

    private void record(String prompt, State state, int tokens) {
        synchronized (calls) {
            calls.put(prompt, new ModelCall(prompt, state, tokens));
        }
    }
}
