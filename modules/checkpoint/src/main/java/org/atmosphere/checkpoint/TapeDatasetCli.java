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
package org.atmosphere.checkpoint;

import org.atmosphere.ai.tape.TapeTrainingExtractor;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Command-line tool: read a durable SQLite session tape and emit chat-format
 * JSONL fine-tuning data via {@link TapeTrainingExtractor} — the runnable bridge
 * between the tape ({@link SqliteTapeStore}, in this module) and the extractor
 * ({@code atmosphere-ai}, which cannot depend on this module).
 *
 * <pre>
 *   java -cp … org.atmosphere.checkpoint.TapeDatasetCli &lt;tape.db&gt; &lt;out.jsonl&gt; [maxRuns]
 * </pre>
 *
 * <p>Prints the extraction report (runs seen, examples emitted, skips by reason)
 * to stderr so the drop accounting is visible; never fabricates examples for
 * runs that lack an input or completion.</p>
 */
public final class TapeDatasetCli {

    private TapeDatasetCli() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: TapeDatasetCli <tape.db> <out.jsonl> [maxRuns]");
            System.exit(2);
            return;
        }
        var dbPath = Path.of(args[0]);
        var out = Path.of(args[1]);
        int maxRuns = 0;
        if (args.length > 2) {
            try {
                maxRuns = Integer.parseInt(args[2]);
            } catch (NumberFormatException e) {
                System.err.println("maxRuns must be an integer, got: " + args[2]);
                System.err.println("usage: TapeDatasetCli <tape.db> <out.jsonl> [maxRuns]");
                System.exit(2);
                return;
            }
        }

        if (!Files.exists(dbPath)) {
            System.err.println("tape db not found: " + dbPath);
            System.exit(1);
            return;
        }

        try (var store = new SqliteTapeStore(dbPath)) {
            var result = new TapeTrainingExtractor().extract(store, maxRuns);
            try (var w = new BufferedWriter(Files.newBufferedWriter(out, StandardCharsets.UTF_8))) {
                TapeTrainingExtractor.writeJsonl(result.examples(), w);
            }
            var r = result.report();
            System.err.printf("tape %s -> %s%n", dbPath.getFileName(), out.getFileName());
            System.err.printf("  runs seen        : %d%n", r.runsSeen());
            System.err.printf("  examples emitted : %d%n", r.emitted());
            System.err.printf("  skipped (not terminal / no input / no output): %d / %d / %d%n",
                    r.skippedNotTerminal(), r.skippedNoInput(), r.skippedNoOutput());
            for (var note : r.notes()) {
                System.err.println("    " + note);
            }
        }
    }
}
