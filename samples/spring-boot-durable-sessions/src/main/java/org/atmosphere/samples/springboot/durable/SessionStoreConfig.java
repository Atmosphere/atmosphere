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
package org.atmosphere.samples.springboot.durable;

import java.nio.file.Path;

import org.atmosphere.session.SessionStore;
import org.atmosphere.session.sqlite.SqliteSessionStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides a SQLite-backed {@link SessionStore} so sessions survive
 * server restarts. The database file is stored next to the application.
 */
@Configuration
public class SessionStoreConfig {

    @Bean
    public SessionStore sessionStore() {
        return new SqliteSessionStore(Path.of("data/sessions.db"));
    }
}
