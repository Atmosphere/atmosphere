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
package org.atmosphere.samples.kotlindsl

import jakarta.servlet.http.HttpServlet
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse

/**
 * Embedded-Jetty stand-in for the starters' `/api/console/info` endpoint, so
 * the bundled Atmosphere Console configures itself for this sample: the
 * broadcast dialect (`{author, message, time}` envelopes) over the Kotlin DSL
 * chat at `/chat`, on the default Atmosphere transport.
 */
class ConsoleInfoServlet : HttpServlet() {

    override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
        resp.status = HttpServletResponse.SC_OK
        resp.contentType = "application/json;charset=UTF-8"
        resp.setHeader("Cache-Control", "no-cache")
        resp.writer.write(INFO_JSON)
    }

    private companion object {
        const val INFO_JSON = """{"subtitle":"Kotlin DSL chat — the transport and agent DSLs on embedded Jetty",""" +
            """"endpoint":"/chat","runtime":"none","mode":"broadcast","transport":"atmosphere",""" +
            """"hasInteractions":false,"hasVerifier":false,"hasAdmin":false,"hasWorkspace":false}"""
    }
}

/**
 * Serves the Console's page at `/atmosphere/console/`: the directory URL the
 * root redirect and the Console's own links use. Its assets are served by the
 * ResourceServlet.
 */
class ConsoleIndexServlet : HttpServlet() {

    override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
        val page = ConsoleIndexServlet::class.java.classLoader.getResourceAsStream(INDEX)
        if (page == null) {
            resp.sendError(HttpServletResponse.SC_NOT_FOUND)
            return
        }
        resp.status = HttpServletResponse.SC_OK
        resp.contentType = "text/html;charset=UTF-8"
        resp.setHeader("Cache-Control", "no-cache")
        page.use { it.transferTo(resp.outputStream) }
    }

    private companion object {
        const val INDEX = "webapp/atmosphere/console/index.html"
    }
}

/**
 * Sends the sample's root to the Atmosphere Console, keeping the query string:
 * `/?token=x` lands on `/atmosphere/console/?token=x`, so the Console opens
 * with its token. Answers GET and HEAD alike.
 */
class ConsoleRootRedirectServlet : HttpServlet() {

    override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) = redirect(req, resp)

    override fun doHead(req: HttpServletRequest, resp: HttpServletResponse) = redirect(req, resp)

    private fun redirect(req: HttpServletRequest, resp: HttpServletResponse) {
        val query = req.queryString
        resp.status = HttpServletResponse.SC_FOUND
        resp.setHeader("Location", CONSOLE_PATH + if (query.isNullOrEmpty()) "" else "?$query")
    }

    companion object {
        /** Where the Console is served. */
        const val CONSOLE_PATH = "/atmosphere/console/"
    }
}
