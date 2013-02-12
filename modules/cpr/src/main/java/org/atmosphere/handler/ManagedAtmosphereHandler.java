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
package org.atmosphere.handler;

import org.atmosphere.config.service.Delete;
import org.atmosphere.config.service.Disconnect;
import org.atmosphere.config.service.Get;
import org.atmosphere.config.service.Message;
import org.atmosphere.config.service.Resume;
import org.atmosphere.config.service.Post;
import org.atmosphere.config.service.Put;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public class ManagedAtmosphereHandler implements AtmosphereHandler {

    private Logger logger = LoggerFactory.getLogger(ManagedAtmosphereHandler.class);

    private final Object object;
    private final Method onMessageMethod;
    private final Method onDisconnectMethod;
    private final Method onTimeoutMethod;
    private final Method onGetMethod;
    private final Method onPostMethod;
    private final Method onPutMethod;
    private final Method onDeleteMethod;

    public ManagedAtmosphereHandler(Object c) {
        this.object = c;
        this.onMessageMethod = populate(c, Message.class);
        this.onDisconnectMethod = populate(c, Disconnect.class);
        this.onTimeoutMethod = populate(c, Resume.class);
        this.onGetMethod = populate(c, Get.class);
        this.onPostMethod = populate(c, Post.class);
        this.onPutMethod = populate(c, Put.class);
        this.onDeleteMethod = populate(c, Delete.class);

    }


    @Override
    public void onRequest(AtmosphereResource resource) throws IOException {
        AtmosphereRequest request = resource.getRequest();
        String method = request.getMethod();
        if (method.equalsIgnoreCase("get")) {
            invoke(onGetMethod, resource);
        } else if (method.equalsIgnoreCase("post")) {
            invoke(onPostMethod, resource);            
        } else if (method.equalsIgnoreCase("delete")) {
            invoke(onDeleteMethod, resource);            
        } else if (method.equalsIgnoreCase("put")) {
            invoke(onPutMethod, resource);           
        }
    }

    @Override
    public void onStateChange(AtmosphereResourceEvent event) throws IOException {
        
        AtmosphereResource resource = event.getResource();
        if (event.isCancelled()) {
            invoke(onDisconnectMethod, resource);
        } else if (event.isResumedOnTimeout() || event.isResuming()) {
            invoke(onTimeoutMethod, resource);
        } else {
            String message = event.getMessage().toString();
            invoke(onMessageMethod, event);
        }

    }

    @Override
    public void destroy() {
    }

    private Method populate(Object c, Class<? extends Annotation> annotation) {
        for (Method m : c.getClass().getMethods()) {
            if (m.isAnnotationPresent(annotation)) {
                return m;
            }
        }
        return null;
    }

    private Object invoke(Method m, Object o) {
        if (m != null) {
            try {
                return m.invoke(object, o == null ? new Object[]{} : new Object[]{o});
            } catch (IllegalAccessException e) {
                logger.debug("", e);
            } catch (InvocationTargetException e) {
                logger.debug("", e);
            }
        }
        return null;
    }
}
