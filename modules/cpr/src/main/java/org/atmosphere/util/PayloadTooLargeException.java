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
package org.atmosphere.util;

import java.io.IOException;

/**
 * A request body larger than the limit it was read with; the caller answers
 * {@code 413}. Nothing past the limit was read into memory.
 */
public class PayloadTooLargeException extends IOException {

    private static final long serialVersionUID = 1L;

    private final long limit;

    public PayloadTooLargeException(long limit) {
        super("Request body exceeds " + limit + " bytes");
        this.limit = limit;
    }

    /** The limit, in bytes, the body was read with. */
    public long limit() {
        return limit;
    }
}
