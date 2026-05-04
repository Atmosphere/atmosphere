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
package org.atmosphere.samples.quarkus.aichat;

/**
 * Structured output type for movie review extraction. The LLM produces JSON
 * conforming to this record's schema; the Atmosphere AI pipeline parses the
 * response and emits {@code EntityStart} / {@code StructuredField} /
 * {@code EntityComplete} events on the wire. Identical shape to the
 * Spring Boot sibling sample.
 */
public record MovieReview(String title, int rating, String summary, String sentiment) {}
