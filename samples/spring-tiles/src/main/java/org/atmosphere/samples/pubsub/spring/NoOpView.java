/**
 * 
 */
package org.atmosphere.samples.pubsub.spring;

import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.web.servlet.View;

/**
 * Requests handled by Atmosphere should not get rendered as Views
 * 
 * @author westraj
 */
public class NoOpView implements View {

	private String contentType;
	
	/**
	 * 
	 */
	public NoOpView() {
		
	}

	/**
	 * Renders nothing.
	 * @see #renderMergedOutputModel
	 */
	
	public void render(Map<String, ?> model, HttpServletRequest request, HttpServletResponse response) throws Exception {
		// do not render anything.
		// Request has not UI.  It was an Atmosphere-related request
	}

	
	public String getContentType() {
		return contentType;
	}
	
	public void setContentType(String contentType) {
		this.contentType = contentType;
	}

}
