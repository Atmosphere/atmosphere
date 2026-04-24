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
package org.atmosphere.ai.policy.cedar;

import org.atmosphere.ai.governance.GovernancePolicy;
import org.atmosphere.ai.governance.PolicyParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

/**
 * {@link PolicyParser} that wraps a Cedar policy module in a
 * {@link CedarPolicy} adapter. Format identifier {@code "cedar"}.
 *
 * <p>Cedar doesn't have a {@code package} declaration, so the parser
 * derives the policy name from the first {@code @id("...")} annotation
 * it finds, falling back to the source URI tail.</p>
 */
public final class CedarPolicyParser implements PolicyParser {

    private static final Pattern ID_ANNOTATION = Pattern.compile(
            "@id\\(\"([^\"]+)\"\\)");

    private final CedarAuthorizer authorizer;

    /** Default — shells out to the {@code cedar} CLI on PATH. */
    public CedarPolicyParser() {
        this(new CedarCliAuthorizer());
    }

    public CedarPolicyParser(CedarAuthorizer authorizer) {
        if (authorizer == null) {
            throw new IllegalArgumentException("authorizer must not be null");
        }
        this.authorizer = authorizer;
    }

    @Override
    public String format() {
        return "cedar";
    }

    @Override
    public List<GovernancePolicy> parse(String source, InputStream in) throws IOException {
        if (in == null) {
            return List.of();
        }
        var cedar = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        var matcher = ID_ANNOTATION.matcher(cedar);
        var name = matcher.find() ? matcher.group(1) : deriveNameFromSource(source);
        return List.of(new CedarPolicy(
                name,
                source == null ? "cedar:<unknown>" : source,
                "1.0",
                cedar,
                authorizer));
    }

    private static String deriveNameFromSource(String source) {
        if (source == null || source.isBlank()) return "atmosphere.governance.cedar";
        var tail = source.contains("/")
                ? source.substring(source.lastIndexOf('/') + 1)
                : source;
        if (tail.endsWith(".cedar")) {
            tail = tail.substring(0, tail.length() - ".cedar".length());
        }
        return "cedar:" + tail;
    }
}
