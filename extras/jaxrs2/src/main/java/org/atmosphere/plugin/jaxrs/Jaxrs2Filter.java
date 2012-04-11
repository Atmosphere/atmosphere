/*
 * Copyright 2012 Jeanfrancois Arcand
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
package org.atmosphere.plugin.jaxrs;

import com.sun.jersey.api.model.AbstractMethod;
import com.sun.jersey.spi.container.ResourceFilter;
import org.atmosphere.jersey.AtmosphereFilter;

import javax.ws.rs.Suspend;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class Jaxrs2Filter extends AtmosphereFilter{

    @Override
    public List<ResourceFilter> create(AbstractMethod am) {
        List<ResourceFilter> filters = super.create(am);

        if (am.isAnnotationPresent(Suspend.class)) {
            long suspendTimeout = am.getAnnotation(Suspend.class).timeOut() == 0 ? -1 : am.getAnnotation(Suspend.class).timeOut();
            TimeUnit tu = am.getAnnotation(Suspend.class).timeUnit();
            suspendTimeout = translateTimeUnit(suspendTimeout, tu);

            Filter f = new Filter(Action.SUSPEND, suspendTimeout, 0, org.atmosphere.annotation.Suspend.SCOPE.APPLICATION, false);

            filters.add(f);
        }

        return filters;
    }

}
