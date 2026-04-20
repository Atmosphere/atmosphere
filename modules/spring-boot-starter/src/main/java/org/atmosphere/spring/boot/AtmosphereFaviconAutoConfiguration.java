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
package org.atmosphere.spring.boot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;

/**
 * Serves {@code /favicon.ico} and {@code /favicon.png} from the Atmosphere
 * logo bytes shipped inside this starter, so browsers stop logging a 404
 * on the default favicon request for the admin UI, the console UI, and
 * every Atmosphere-based sample that has no favicon of its own.
 *
 * <p>Disable with {@code atmosphere.favicon.enabled=false} when the
 * application supplies its own {@code /favicon.ico} via static resources
 * or a competing controller mapping.</p>
 */
@AutoConfiguration
@ConditionalOnWebApplication
@ConditionalOnProperty(name = "atmosphere.favicon.enabled",
        havingValue = "true", matchIfMissing = true)
public class AtmosphereFaviconAutoConfiguration {

    @Bean
    public AtmosphereFaviconController atmosphereFaviconController() {
        return new AtmosphereFaviconController();
    }

    /**
     * Single mapping over {@code /favicon.ico} and {@code /favicon.png}.
     * Both return the same PNG bytes — the {@code .ico} path is what
     * browsers auto-request at the site root when no {@code <link
     * rel="icon">} tag declares otherwise; the {@code .png} alias is
     * kept for HTML pages that reference it explicitly.
     */
    @RestController
    public static class AtmosphereFaviconController {

        private static final Logger logger =
                LoggerFactory.getLogger(AtmosphereFaviconController.class);
        private static final String RESOURCE =
                "/META-INF/resources/atmosphere/favicon.png";

        private final byte[] bytes;

        public AtmosphereFaviconController() {
            this.bytes = loadFavicon();
        }

        private static byte[] loadFavicon() {
            try (InputStream is = AtmosphereFaviconController.class
                    .getResourceAsStream(RESOURCE)) {
                if (is == null) {
                    logger.warn("Atmosphere favicon resource not found at {}",
                            RESOURCE);
                    return new byte[0];
                }
                return is.readAllBytes();
            } catch (IOException e) {
                logger.warn("Failed to load Atmosphere favicon: {}", e.getMessage());
                return new byte[0];
            }
        }

        @GetMapping({"/favicon.ico", "/favicon.png"})
        public ResponseEntity<byte[]> favicon() {
            if (bytes.length == 0) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_PNG)
                    .header("Cache-Control", "public, max-age=86400")
                    .body(bytes);
        }
    }
}
