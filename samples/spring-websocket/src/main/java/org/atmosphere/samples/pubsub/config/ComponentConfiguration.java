/*
 * Copyright 2013 Jeanfrancois Arcand
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
/**
 * This code was donated by Dan Vulpe https://github.com/dvulpe/atmosphere-ws-pubsub
 */
package org.atmosphere.samples.pubsub.config;

import org.apache.commons.lang.StringUtils;
import org.atmosphere.cpr.BroadcasterFactory;
import org.codehaus.jackson.map.MapperConfig;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.map.PropertyNamingStrategy;
import org.codehaus.jackson.map.introspect.AnnotatedField;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Import;

@Configuration
@Import(WebAppConfiguration.class)
@ComponentScan(basePackages = "org.atmosphere.samples.pubsub", excludeFilters = @ComponentScan.Filter(Configuration.class))
@EnableAspectJAutoProxy(proxyTargetClass = true)
public class ComponentConfiguration {

    @Bean
    public BroadcasterFactory broadcasterFactory() {
        return BroadcasterFactory.getDefault();
    }

    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.setPropertyNamingStrategy(new PropertyNamingStrategy() {
            @Override
            public String nameForField(MapperConfig<?> config, AnnotatedField field, String defaultName) {
                return StringUtils.uncapitalize(field.getName());
            }
        });
        return mapper;
    }

}
