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
package org.atmosphere.samples.springboot.agui;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@SpringBootApplication
public class AgUiChatApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgUiChatApplication.class, args);
    }

    /**
     * Serve the bespoke AG-UI / CopilotKit-style React chat UI at the root path.
     * AG-UI (not the generic Atmosphere console) is the point of this sample, so
     * {@code /} forwards to the built React bundle in {@code static/index.html}
     * rather than redirecting to {@code /atmosphere/console/}. The Atmosphere
     * console remains available at {@code /atmosphere/console/} for inspection.
     */
    @Configuration
    static class HomePage implements WebMvcConfigurer {
        @Override
        public void addViewControllers(ViewControllerRegistry registry) {
            registry.addViewController("/").setViewName("forward:/index.html");
        }
    }
}
