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
package org.atmosphere.samples.adminbundle;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Minimal Spring Boot host whose only Atmosphere dependency is
 * {@code atmosphere-admin-bundle}. There is no application code beyond this
 * entry point on purpose — the bundle's job is wiring, so the proof lives in
 * {@code AdminBundleWiringTest}, which boots this context and asserts the
 * auto-configured beans the single bundle dependency brings in.
 */
@SpringBootApplication
public class AdminBundleApplication {

    public static void main(String[] args) {
        SpringApplication.run(AdminBundleApplication.class, args);
    }
}
