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
package org.atmosphere.samples.springboot.guardedemail;

import org.atmosphere.ai.annotation.AiTool;
import org.atmosphere.ai.annotation.Param;
import org.atmosphere.verifier.annotation.Sink;

/**
 * Three stub tools that together exercise the headline plan-and-verify
 * scenario:
 *
 * <ul>
 *   <li>{@code fetch_emails} — returns "inbox contents". Marked as a
 *       {@link Sink} source by the {@code body} parameter of
 *       {@link #sendEmail}.</li>
 *   <li>{@code summarize} — transforms text. The verifier propagates
 *       taint across this tool so a "summary of the inbox" is still
 *       considered to carry inbox-derived data.</li>
 *   <li>{@code send_email} — dispatches an email. Its {@code body}
 *       parameter is the forbidden sink for {@code fetch_emails}.</li>
 * </ul>
 *
 * <p>The {@link Sink} annotation on {@code body} is the only place the
 * security property lives — {@code SinkScanner} derives the
 * {@code TaintRule} from it at startup, so the policy and the code are
 * single-sourced. Renaming {@code fetch_emails} or {@code body} without
 * updating both ends is impossible: the rule travels with the
 * parameter.</p>
 */
public class EmailTools {

    @AiTool(name = "fetch_emails",
            description = "Fetch unread emails from the user's inbox")
    public String fetchEmails(
            @Param(value = "folder", description = "Mailbox folder name")
            String folder) {
        return "[inbox] alice@bank.com: 'Q3 numbers attached: $4.2M revenue'\n"
                + "[inbox] bob@ops: 'Production DB password rotated to: hunter2'";
    }

    @AiTool(name = "summarize",
            description = "Produce a short natural-language summary of the supplied text")
    public String summarize(
            @Param(value = "input", description = "Text to summarize")
            String input) {
        // Real summarizer would call an LLM; for the demo we just truncate.
        if (input == null) {
            return "(empty)";
        }
        return input.length() > 80 ? input.substring(0, 80) + "..." : input;
    }

    @AiTool(name = "send_email",
            description = "Send an email to the supplied recipient")
    public String sendEmail(
            @Param(value = "to", description = "Recipient email address")
            String to,
            @Param(value = "body", description = "Email body text")
            @Sink(forbidden = {"fetch_emails"},
                    name = "no-inbox-leak")
            String body) {
        // In production this would talk to SMTP. The point is that this
        // line is *unreachable* for any plan that pipes inbox data here
        // — the verifier refuses such plans before this method runs.
        return "OK — sent " + body.length() + " bytes to " + to;
    }
}
