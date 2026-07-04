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
package org.atmosphere.ai.adk;

import com.google.adk.artifacts.BaseArtifactService;
import com.google.adk.artifacts.ListArtifactsResponse;
import com.google.common.collect.ImmutableList;
import com.google.genai.types.Part;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Maybe;
import io.reactivex.rxjava3.core.Single;
import org.atmosphere.ai.fs.AgentFileSystem;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * ADK {@link BaseArtifactService} bridging the framework's native artifact
 * surface onto Atmosphere's bounded, conversation-scoped
 * {@link AgentFileSystem} store — the model reads artifacts through ADK's own
 * {@code load_artifacts} tool and writes them via
 * {@code ToolContext.saveArtifact(...)} ({@link AdkSaveArtifactTool}), and
 * every byte lands in the same {@code files/{conversationId}/} workspace the
 * portable file tools use. Injected per request via
 * {@code Runner.Builder.artifactService(...)} in
 * {@link AdkAgentRuntime#buildRequestRunner} — this is what makes
 * {@link org.atmosphere.ai.AiCapability#VIRTUAL_FILESYSTEM} an honest
 * capability on the ADK runtime (Correctness Invariant #5).
 *
 * <h2>Versioning mapping</h2>
 * ADK artifacts are integer-versioned; {@link AgentFileSystem} keeps no
 * history, so versions map to <em>overwrite semantics</em>:
 * {@link #saveArtifact} always replaces the file and reports version
 * {@value #CURRENT_VERSION}; {@link #listVersions} reports at most
 * {@code [0]}; {@link #loadArtifact} serves the current content for a
 * {@code null} or {@code 0} version request and completes empty for any
 * other version (no such snapshot exists). {@link #deleteArtifact} signals
 * {@link UnsupportedOperationException} — the store exposes no delete.
 *
 * <h2>Scoping</h2>
 * The ADK {@code appName}/{@code userId}/{@code sessionId} keys are
 * deliberately ignored: each instance is constructed per request around the
 * already conversation-scoped store, so ADK-side keys cannot widen (or leak
 * across) Atmosphere's scope.
 *
 * <h2>Content mapping</h2>
 * The store is UTF-8 text only. Text {@link Part}s pass through; inline-data
 * parts are accepted only when their bytes decode as strict UTF-8. Anything
 * else is rejected with a clear {@link IllegalArgumentException} carried on
 * the reactive error channel (Correctness Invariant #4 — validate at the
 * boundary, never mutate binary payloads into text). Bounds and traversal
 * rejections from the underlying store propagate the same way.
 */
public final class AdkArtifactService implements BaseArtifactService {

    /** The only version a history-less, overwrite-only store ever reports. */
    static final int CURRENT_VERSION = 0;

    private final AgentFileSystem fileSystem;

    /**
     * Create a bridge over one conversation-scoped store.
     *
     * @param fileSystem the bounded store to expose (never {@code null})
     */
    public AdkArtifactService(AgentFileSystem fileSystem) {
        if (fileSystem == null) {
            throw new IllegalArgumentException("fileSystem must not be null");
        }
        this.fileSystem = fileSystem;
    }

    @Override
    public Single<Integer> saveArtifact(String appName, String userId, String sessionId,
                                        String filename, Part artifact) {
        return Single.fromCallable(() -> {
            fileSystem.write(filename, textOf(filename, artifact));
            return CURRENT_VERSION;
        });
    }

    @Override
    public Maybe<Part> loadArtifact(String appName, String userId, String sessionId,
                                    String filename, Integer version) {
        return Maybe.defer(() -> {
            if (version != null && version != CURRENT_VERSION) {
                // Overwrite semantics: no historical snapshot exists for any
                // version other than the current one.
                return Maybe.empty();
            }
            if (!exists(filename)) {
                return Maybe.empty();
            }
            return Maybe.just(Part.fromText(fileSystem.read(filename)));
        });
    }

    @Override
    public Single<ListArtifactsResponse> listArtifactKeys(String appName, String userId,
                                                          String sessionId) {
        return Single.fromCallable(() -> ListArtifactsResponse.builder()
                .filenames(fileSystem.glob("**"))
                .build());
    }

    @Override
    public Completable deleteArtifact(String appName, String userId, String sessionId,
                                      String filename) {
        return Completable.error(new UnsupportedOperationException(
                "Atmosphere's AgentFileSystem is overwrite-only and exposes no delete "
                        + "operation — artifact '" + filename + "' cannot be deleted"));
    }

    @Override
    public Single<ImmutableList<Integer>> listVersions(String appName, String userId,
                                                       String sessionId, String filename) {
        return Single.fromCallable(() -> {
            if (exists(filename)) {
                return ImmutableList.of(CURRENT_VERSION);
            }
            return ImmutableList.of();
        });
    }

    /**
     * Existence probe via the store's own {@code ls} — traversal attempts in
     * {@code filename} surface as {@link IllegalArgumentException} from the
     * store's boundary validation and propagate on the reactive error channel.
     */
    private boolean exists(String filename) {
        var trimmed = filename == null ? "" : filename.trim();
        var slash = trimmed.lastIndexOf('/');
        var parent = slash < 0 ? null : trimmed.substring(0, slash);
        for (var entry : fileSystem.ls(parent)) {
            if (!entry.directory() && entry.path().equals(trimmed)) {
                return true;
            }
        }
        return false;
    }

    private static String textOf(String filename, Part artifact) {
        if (artifact == null) {
            throw new IllegalArgumentException("artifact part must not be null");
        }
        var text = artifact.text();
        if (text.isPresent()) {
            return text.get();
        }
        var blob = artifact.inlineData();
        if (blob.isPresent() && blob.get().data().isPresent()) {
            var decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            try {
                return decoder.decode(ByteBuffer.wrap(blob.get().data().get())).toString();
            } catch (CharacterCodingException e) {
                throw new IllegalArgumentException("Artifact '" + filename
                        + "' is not valid UTF-8 text — Atmosphere's AgentFileSystem "
                        + "stores text only", e);
            }
        }
        throw new IllegalArgumentException("Artifact '" + filename
                + "' carries no text or inline-data payload — only text artifacts "
                + "are supported");
    }
}
