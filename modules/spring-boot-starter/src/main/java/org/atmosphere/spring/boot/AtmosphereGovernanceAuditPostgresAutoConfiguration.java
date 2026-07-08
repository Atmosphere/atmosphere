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

import org.atmosphere.ai.audit.postgres.JdbcAuditSink;
import org.atmosphere.ai.governance.AsyncAuditSink;
import org.atmosphere.ai.governance.AuditSink;
import org.atmosphere.ai.governance.GovernanceDecisionLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Points the governance {@link GovernanceDecisionLog} at Postgres (or any JDBC
 * store) so every admit and deny is persisted for long-term retention and
 * compliance queries — the {@code ai-audit-postgres} half of the blog's
 * "the decision log can be pointed at Kafka or Postgres" claim.
 *
 * <p><b>Default OFF (Correctness Invariant #6).</b> This auto-configuration is
 * inert unless BOTH the {@code atmosphere-ai-audit-postgres} module is on the
 * classpath ({@link JdbcAuditSink}) AND
 * {@code atmosphere.ai.governance.audit.postgres.url} is set. With no URL there
 * is no external side effect.</p>
 *
 * <p>The sink is wrapped in {@link AsyncAuditSink} by default so a slow/stalled
 * database never back-pressures the admission thread (Correctness Invariant #3);
 * set {@code atmosphere.ai.governance.audit.async=false} for synchronous
 * in-line persistence.</p>
 *
 * <p>Ownership (Correctness Invariant #1): the sink built here is registered on
 * the installed decision log at startup and explicitly removed and closed on
 * shutdown, so a context refresh does not leak a background drain thread or a
 * dangling sink. The decision log itself is installed over the NOOP only when
 * nothing else has installed one (mirrors {@code AtmosphereAdminAutoConfiguration}
 * and {@code GovernanceMemoryInstaller}), so an operator's own {@code install()}
 * — or the admin module's — is never clobbered.</p>
 */
@AutoConfiguration(after = AtmosphereAdminAutoConfiguration.class)
@ConditionalOnClass({JdbcAuditSink.class, GovernanceDecisionLog.class})
@ConditionalOnProperty(prefix = "atmosphere.ai.governance.audit.postgres", name = "url")
@EnableConfigurationProperties(AtmosphereGovernanceAuditProperties.class)
public class AtmosphereGovernanceAuditPostgresAutoConfiguration
        implements InitializingBean, DisposableBean {

    private static final Logger logger =
            LoggerFactory.getLogger(AtmosphereGovernanceAuditPostgresAutoConfiguration.class);

    private final AtmosphereGovernanceAuditProperties properties;
    private AuditSink installedSink;

    public AtmosphereGovernanceAuditPostgresAutoConfiguration(
            AtmosphereGovernanceAuditProperties properties) {
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        var pg = properties.getPostgres();
        var dataSource = new GovernanceAuditDataSource(
                pg.getUrl(), pg.getUsername(), pg.getPassword());
        AuditSink sink = new JdbcAuditSink(dataSource, pg.getTable(), pg.isAutoCreate());
        if (properties.isAsync()) {
            sink = new AsyncAuditSink(sink, properties.getQueueCapacity());
        }
        // The sink's fan-out only fires on a non-NOOP log. Install one over the
        // NOOP if nothing else has, so persistence works even without the admin
        // module. Never clobber an operator's / admin's own install(...).
        if (GovernanceDecisionLog.installed().capacity() == 0) {
            GovernanceDecisionLog.install(GovernanceDecisionLog.DEFAULT_CAPACITY);
        }
        GovernanceDecisionLog.installed().addSink(sink);
        this.installedSink = sink;
        logger.info("Governance decision log pointed at JDBC audit store: table={}, async={} "
                + "(sink: {})", pg.getTable(), properties.isAsync(), sink.name());
    }

    @Override
    public void destroy() {
        if (installedSink != null) {
            GovernanceDecisionLog.installed().removeSink(installedSink);
            installedSink.close();
            installedSink = null;
        }
    }
}
