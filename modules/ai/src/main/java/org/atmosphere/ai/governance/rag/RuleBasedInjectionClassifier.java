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
package org.atmosphere.ai.governance.rag;

import org.atmosphere.ai.ContextProvider;

import java.util.regex.Pattern;

/**
 * Sub-millisecond rule-based injection classifier. Catches the canonical
 * prompt-injection vectors documented by OWASP / Simon Willison / NVIDIA
 * Morpheus without requiring any model. Brittle on creative phrasings —
 * which is why {@link InjectionClassifier.Tier#EMBEDDING_SIMILARITY} is
 * recommended when an embedding runtime is available.
 *
 * <h2>Pattern catalog</h2>
 * <ul>
 *   <li><b>Instruction override</b> — "ignore previous instructions",
 *       "disregard the system prompt", "forget your rules".</li>
 *   <li><b>Role reversal</b> — "You are now DAN", "act as a different AI",
 *       jailbreak templates.</li>
 *   <li><b>System-prompt spoofing</b> — embedded {@code system:} / {@code SYSTEM:}
 *       markers, fake tool-call responses.</li>
 *   <li><b>Data exfiltration cues</b> — "reveal your system prompt",
 *       "print all previous messages", "output the contents of".</li>
 *   <li><b>Delimiter injection</b> — triple-backtick + fake role / tool
 *       frames inside a document.</li>
 * </ul>
 *
 * <p>Confidence scoring: the first pattern that fires sets the verdict at
 * {@code 0.9}; the rule-based tier does not stack signals, because
 * compound patterns quickly over-match on legitimate content (a document
 * that literally quotes "ignore previous instructions" from a security
 * blog post should not automatically be dropped). Operators who need
 * compound scoring layer the embedding tier on top.</p>
 */
public final class RuleBasedInjectionClassifier implements InjectionClassifier {

    /** Compiled once; each probe is scored independently. */
    private static final Probe[] PROBES = new Probe[] {
            new Probe("instruction-override",
                    "\\b(ignore|disregard|forget)\\b.{0,40}\\b(previous|prior|above|system)\\b"
                            + ".{0,40}\\b(instruction|prompt|rule|directive)s?\\b"),
            new Probe("instruction-override-short",
                    "\\b(ignore|disregard|forget)\\b.{0,40}\\b(instructions?|rules?|prompt)\\b"),
            new Probe("system-prompt-spoof",
                    "(?m)^\\s*(system|assistant|user)\\s*[:>]"),
            new Probe("role-reversal",
                    "\\byou\\s+are\\s+(now|actually|secretly)\\b"),
            new Probe("jailbreak-template",
                    "\\b(DAN|STAN|DUDE|Developer\\s+Mode|do\\s+anything\\s+now)\\b"),
            new Probe("exfiltration-prompt",
                    "\\b(reveal|print|output|echo|show\\s+me)\\b.{0,40}"
                            + "\\b(system\\s+prompt|instruction|hidden|secret)\\b"),
            new Probe("exfiltration-history",
                    "\\b(print|output|reveal)\\b.{0,40}\\b(previous|prior|all)\\b"
                            + ".{0,20}\\b(messages?|turns?|conversation|history)\\b"),
            new Probe("delimiter-injection",
                    "(?s)```.{0,20}(system|assistant|tool)\\s*[:>].{0,200}```"),
            new Probe("tool-call-spoof",
                    "\\b(call|invoke|execute)\\b.{0,20}\\btool\\b.{0,40}\\b(delete|remove|drop)\\b"),
    };

    @Override
    public InjectionClassifier.Tier tier() {
        return InjectionClassifier.Tier.RULE_BASED;
    }

    @Override
    public Decision evaluate(ContextProvider.Document document) {
        if (document == null || document.content() == null || document.content().isBlank()) {
            return Decision.safe(1.0);
        }
        var content = document.content();
        for (var probe : PROBES) {
            var matcher = probe.pattern.matcher(content);
            if (matcher.find()) {
                return Decision.injected(
                        "injection probe '" + probe.name + "' matched: '"
                                + truncate(matcher.group()) + "'",
                        0.9);
            }
        }
        return Decision.safe(1.0);
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 80 ? s.substring(0, 80) + "…" : s.replace('\n', ' ');
    }

    private record Probe(String name, Pattern pattern) {
        Probe(String name, String regex) {
            this(name, Pattern.compile(regex, Pattern.CASE_INSENSITIVE));
        }
    }
}
