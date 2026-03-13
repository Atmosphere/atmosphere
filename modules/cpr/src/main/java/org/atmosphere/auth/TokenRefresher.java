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
package org.atmosphere.auth;

import java.util.Optional;

/**
 * Optional SPI for server-side token refresh. When a {@link TokenValidator} returns
 * {@link TokenValidator.Expired}, the {@link org.atmosphere.interceptor.AuthInterceptor}
 * will attempt a refresh if a {@code TokenRefresher} is configured.
 *
 * <p>If the refresh succeeds, the new token is sent back to the client via the
 * {@code X-Atmosphere-Auth-Refresh} header or response body, depending on the transport.</p>
 *
 * @since 4.0
 */
@FunctionalInterface
public interface TokenRefresher {

    /**
     * Attempt to issue a new token for an expired one.
     *
     * @param expiredToken the expired token string
     * @return the new token if refresh succeeded, or empty if the token cannot be refreshed
     *         (e.g. refresh window exceeded, user revoked)
     */
    Optional<String> refresh(String expiredToken);
}
