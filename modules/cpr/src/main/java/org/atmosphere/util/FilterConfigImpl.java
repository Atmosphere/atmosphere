/*
 * Copyright 2015 Async-IO.org
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
/* This file incorporates work covered by the following copyright and
 * permission notice:
 *
 * Copyright 2004 The Apache Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.atmosphere.util;

import javax.servlet.Filter;
import javax.servlet.FilterConfig;
import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import java.util.Enumeration;

/**
 * {@link FilterConfig} implementation.
 *
 * @author Jeanfrancois Arcand
 */
public final class FilterConfigImpl implements FilterConfig {

    /**
     * The Context with which we are associated.
     */
    private final ServletConfig sc;
    /**
     * The application Filter we are configured for.
     */
    private Filter filter;
    /**
     * Filter name
     */
    private String filterName;

    // ------------------------------------------------------------------ //

    public FilterConfigImpl(ServletConfig sc) {
        this.sc = sc;
    }

    @Override
    public String getInitParameter(String name) {
        return sc.getInitParameter(name);
    }

    @Override
    public String getFilterName() {
        return filterName;
    }

    @Override
    public Enumeration getInitParameterNames() {
        return sc.getInitParameterNames();
    }

    @Override
    public ServletContext getServletContext() {
        return sc.getServletContext();
    }

    /**
     * Return the application Filter we are configured for.
     */
    public Filter getFilter() {
        return filter;
    }

    /**
     * Release the Filter instance associated with this FilterConfig,
     * if there is one.
     */
    public void recycle() {
        if (this.filter != null) {
            filter.destroy();
        }
        this.filter = null;
    }

    /**
     * Set the {@link Filter} associated with this object.
     *
     * @param filter
     */
    public void setFilter(Filter filter) {
        this.filter = filter;
    }

    /**
     * Set the {@link Filter}'s name associated with this object.
     *
     * @param filterName
     */
    public void setFilterName(String filterName) {
        this.filterName = filterName;
    }
}
