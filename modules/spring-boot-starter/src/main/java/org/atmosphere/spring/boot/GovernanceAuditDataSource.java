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

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;

import javax.sql.DataSource;

/**
 * Minimal {@link DataSource} that opens a fresh {@link DriverManager}
 * connection per call from a URL + optional credentials. Dependency-free (no
 * connection-pool library required) so the governance audit sink can be wired
 * purely from {@code atmosphere.ai.governance.audit.postgres.*} properties.
 *
 * <p>Every {@link org.atmosphere.ai.audit.postgres.JdbcAuditSink} write opens
 * and closes its own connection via try-with-resources, so there is no shared
 * mutable connection state and nothing for this class to close (Correctness
 * Invariant #1: it owns no long-lived resource). Operators who need pooling
 * (HikariCP) should instead expose their own {@link DataSource} and register a
 * sink directly — this class is the zero-config path.</p>
 */
final class GovernanceAuditDataSource implements DataSource {

    private final String url;
    private final String username;
    private final String password;
    private PrintWriter logWriter;
    private int loginTimeout;

    GovernanceAuditDataSource(String url, String username, String password) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("url must not be blank");
        }
        this.url = url;
        this.username = username;
        this.password = password;
    }

    @Override
    public Connection getConnection() throws SQLException {
        if (username == null) {
            return DriverManager.getConnection(url);
        }
        return DriverManager.getConnection(url, username, password);
    }

    @Override
    public Connection getConnection(String user, String pass) throws SQLException {
        return DriverManager.getConnection(url, user, pass);
    }

    @Override
    public PrintWriter getLogWriter() {
        return logWriter;
    }

    @Override
    public void setLogWriter(PrintWriter out) {
        this.logWriter = out;
    }

    @Override
    public void setLoginTimeout(int seconds) {
        this.loginTimeout = seconds;
    }

    @Override
    public int getLoginTimeout() {
        return loginTimeout;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException("java.util.logging is not used");
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        throw new SQLException(getClass().getName() + " is not a wrapper for " + iface);
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface.isInstance(this);
    }
}
