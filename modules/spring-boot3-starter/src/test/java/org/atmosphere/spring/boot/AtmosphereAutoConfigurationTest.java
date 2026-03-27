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

import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereServlet;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.ServletRegistrationBean;

import static org.assertj.core.api.Assertions.assertThat;

class AtmosphereAutoConfigurationTest {

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AtmosphereAutoConfiguration.class));

    @Test
    void beansAreCreated() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(AtmosphereServlet.class);
            assertThat(context).hasSingleBean(AtmosphereFramework.class);
            assertThat(context).hasSingleBean(SpringAtmosphereObjectFactory.class);
            assertThat(context).hasSingleBean(ServletRegistrationBean.class);
        });
    }

    @Test
    void defaultServletPath() {
        contextRunner.run(context -> {
            @SuppressWarnings("unchecked")
            ServletRegistrationBean<AtmosphereServlet> registration =
                    context.getBean(ServletRegistrationBean.class);
            assertThat(registration.getUrlMappings()).containsExactly("/atmosphere/*");
        });
    }

    @Test
    void customServletPath() {
        contextRunner.withPropertyValues("atmosphere.servlet-path=/ws/*")
                .run(context -> {
                    @SuppressWarnings("unchecked")
                    ServletRegistrationBean<AtmosphereServlet> registration =
                            context.getBean(ServletRegistrationBean.class);
                    assertThat(registration.getUrlMappings()).containsExactly("/ws/*");
                });
    }

    @Test
    void packagesPropertyMappedToInitParam() {
        contextRunner.withPropertyValues("atmosphere.packages=com.example.chat")
                .run(context -> {
                    @SuppressWarnings("unchecked")
                    ServletRegistrationBean<AtmosphereServlet> registration =
                            context.getBean(ServletRegistrationBean.class);
                    assertThat(registration.getInitParameters())
                            .containsEntry(ApplicationConfig.ANNOTATION_PACKAGE, "com.example.chat");
                });
    }

    @Test
    void springObjectFactoryIsSetOnFramework() {
        contextRunner.run(context -> {
            AtmosphereFramework framework = context.getBean(AtmosphereFramework.class);
            assertThat(framework.objectFactory()).isInstanceOf(SpringAtmosphereObjectFactory.class);
        });
    }

    @Test
    void disableAtmosphereInitializerIsSet() {
        contextRunner.run(context -> {
            @SuppressWarnings("unchecked")
            ServletRegistrationBean<AtmosphereServlet> registration =
                    context.getBean(ServletRegistrationBean.class);
            assertThat(registration.getInitParameters())
                    .containsEntry(ApplicationConfig.DISABLE_ATMOSPHERE_INITIALIZER, "true");
        });
    }

    @Test
    void customServletBeanPreventsAutoConfig() {
        contextRunner.withBean("atmosphereServlet", AtmosphereServlet.class, AtmosphereServlet::new)
                .run(context -> {
                    assertThat(context).hasSingleBean(AtmosphereServlet.class);
                });
    }
}
