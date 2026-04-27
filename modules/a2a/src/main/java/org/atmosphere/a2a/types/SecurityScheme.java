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
package org.atmosphere.a2a.types;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Wraps the proto {@code oneof scheme {...}} as a record where exactly one of
 * the five fields is populated. This mirrors the v1.0.0 ProtoJSON shape, which
 * encodes the chosen variant by field-name presence rather than a type
 * discriminator.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SecurityScheme(
    APIKeySecurityScheme apiKeySecurityScheme,
    HTTPAuthSecurityScheme httpAuthSecurityScheme,
    OAuth2SecurityScheme oauth2SecurityScheme,
    OpenIdConnectSecurityScheme openIdConnectSecurityScheme,
    MutualTlsSecurityScheme mtlsSecurityScheme
) {
    public static SecurityScheme apiKey(APIKeySecurityScheme s) {
        return new SecurityScheme(s, null, null, null, null);
    }

    public static SecurityScheme httpAuth(HTTPAuthSecurityScheme s) {
        return new SecurityScheme(null, s, null, null, null);
    }

    public static SecurityScheme oauth2(OAuth2SecurityScheme s) {
        return new SecurityScheme(null, null, s, null, null);
    }

    public static SecurityScheme openIdConnect(OpenIdConnectSecurityScheme s) {
        return new SecurityScheme(null, null, null, s, null);
    }

    public static SecurityScheme mtls(MutualTlsSecurityScheme s) {
        return new SecurityScheme(null, null, null, null, s);
    }
}
