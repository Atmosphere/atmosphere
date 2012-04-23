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
package org.atmosphere.samples.pubsub.spring;

import java.io.IOException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.BroadcasterFactory;
import org.atmosphere.cpr.FrameworkConfig;
import org.atmosphere.cpr.HeaderConfig;
import org.atmosphere.websocket.WebSocketEventListenerAdapter;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.ModelAndView;

/**
 * Example shows a simple Spring Controller that replicates the logic
 * from AtmosphereHandlerPubSub.java
 *
 * URL to access is:
 * 
 * http://localhost:8080/Atmosphere-pubsub-spring-tiles/pubsub/
 * 
 * @author westraj
 * 
 */
@Controller
@RequestMapping("/")
public class PubSubController {
	
	public PubSubController() {
		System.out.println("PubSubController: Constructor - Created!");
	}

	/**
	 * Handles the main page load
	 * 
	 * @param request
	 * @param response
	 * @return ModelAndView
	 */
	@RequestMapping(produces = "text/html")
	public String loadPage() {
		// Returns just the Spring view name which matches the Tile in tiles.xml to load
		return "pubsub.page";
	}

	/**
	 * This method takes a request to subscribe to the topic
	 * 
	 * @param request
	 * @return ModelAndView
	 */
	@RequestMapping(value = "{topic}", method = RequestMethod.GET)
	public ModelAndView subscribe(HttpServletRequest request) throws Exception {
		AtmosphereResource resource = (AtmosphereResource) request
				.getAttribute(FrameworkConfig.ATMOSPHERE_RESOURCE);

		this.doGet(resource, request, resource.getResponse());

		// A NoOpView is returned to tell Spring Dispatcher framework not to render anything
		// since it is all Atmosphere-related code
		ModelAndView mv = new ModelAndView(new NoOpView());
		return mv;
	}

	/**
	 * Takes a request to post data and broadcasts it to everyone else.
	 * 
	 * @param request
	 * @return String
	 */
	@RequestMapping(value = "{topic}", method = RequestMethod.POST)
	public ModelAndView broadcastMessage(HttpServletRequest request)
			throws Exception {
		
		this.doPost(request);

		// A NoOpView is returned to tell Spring Dispatcher framework not to render anything
		// since it is all Atmosphere-related code
		ModelAndView mv = new ModelAndView(new NoOpView());
		return mv;
	}

	// See AtmosphereHandlerPubSub example - same code as GET
	private void doGet(
			AtmosphereResource r,
			HttpServletRequest req, HttpServletResponse res) {
		// Log all events on the console, including WebSocket events.
		r.addEventListener(new WebSocketEventListenerAdapter());

		res.setContentType("text/html;charset=ISO-8859-1");

		Broadcaster b = lookupBroadcaster(req.getPathInfo());
		r.setBroadcaster(b);

		String header = req.getHeader(HeaderConfig.X_ATMOSPHERE_TRANSPORT);
		if (HeaderConfig.LONG_POLLING_TRANSPORT.equalsIgnoreCase(header)) {
			req.setAttribute(ApplicationConfig.RESUME_ON_BROADCAST,
					Boolean.TRUE);
			r.suspend(-1, false);
		} else {
			r.suspend(-1);
		}
	}

	// See AtmosphereHandlerPubSub example - same code as POST
	private void doPost(HttpServletRequest req) throws IOException {
		Broadcaster b = lookupBroadcaster(req.getPathInfo());
		String message = req.getReader().readLine();

		if (message != null && message.indexOf("message") != -1) {
			b.broadcast(message.substring("message=".length()));
		}
	}

	/**
	 * Retrieve the {@link Broadcaster} based on the request's path info.
	 * 
	 * @param pathInfo
	 * @return the {@link Broadcaster} based on the request's path info.
	 */
    Broadcaster lookupBroadcaster(String pathInfo) {
        if (pathInfo == null) {
            return BroadcasterFactory.getDefault().lookup("/", true);
        } else {
            String[] decodedPath = pathInfo.split("/");
            return BroadcasterFactory.getDefault().lookup(
                    decodedPath[decodedPath.length - 1], true);
        }
    }

}
