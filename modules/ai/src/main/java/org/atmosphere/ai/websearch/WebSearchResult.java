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
package org.atmosphere.ai.websearch;

/**
 * One hit returned by a {@link WebSearchEngine}: a page {@link #title()}, its
 * {@link #url()}, and a short {@link #snippet()} excerpt. Null components are
 * normalized to the empty string so downstream formatting never has to null-check.
 *
 * @param title   the result title
 * @param url     the result URL
 * @param snippet a short excerpt describing the result
 */
public record WebSearchResult(String title, String url, String snippet) {

    public WebSearchResult {
        title = title == null ? "" : title.trim();
        url = url == null ? "" : url.trim();
        snippet = snippet == null ? "" : snippet.trim();
    }
}
