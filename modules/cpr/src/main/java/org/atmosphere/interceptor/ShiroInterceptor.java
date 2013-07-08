package org.atmosphere.interceptor;

import org.apache.shiro.SecurityUtils;
import org.apache.shiro.UnavailableSecurityManagerException;
import org.apache.shiro.subject.Subject;
import org.apache.shiro.web.env.WebEnvironment;
import org.apache.shiro.web.subject.WebSubject;
import org.apache.shiro.web.util.WebUtils;
import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereInterceptor;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResource.TRANSPORT;
import org.atmosphere.cpr.FrameworkConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shiro Interceptor, it creates a request attribute (subject) that contains the true Subject. 
 * For more information about why don't use directly SecurityUtils.getSubject
 * http://jfarcand.wordpress.com/2011/07/13/quick-tip-using-apache-shiro-with-your-atmospheres-websocketcomet-app/
 *
 */
public class ShiroInterceptor implements AtmosphereInterceptor {
    private static final Logger logger = LoggerFactory.getLogger(ShiroInterceptor.class);

    @Override
    public void configure(AtmosphereConfig config) {
    }

    @Override
    public Action inspect(AtmosphereResource r) {
        if (r.getRequest().attributes().containsKey(FrameworkConfig.SECURITY_SUBJECT) == false) {
            try {
                Subject currentUser = null;
                if (r.transport().equals(TRANSPORT.WEBSOCKET)) {
                    WebEnvironment env = WebUtils.getRequiredWebEnvironment(r.getAtmosphereConfig().getServletContext());
                    currentUser = new WebSubject.Builder(env.getSecurityManager(), r.getRequest(), r.getResponse()).buildWebSubject();
                } else {
                    currentUser = SecurityUtils.getSubject();
                }
                if (currentUser != null) {
                    r.getRequest().setAttribute(FrameworkConfig.SECURITY_SUBJECT, currentUser);
                }
            } catch(UnavailableSecurityManagerException ex) {
                logger.info("Shiro Web Security : {}", ex.getMessage());
            } catch(java.lang.IllegalStateException ex) {
                logger.info("Shiro Web Environment : {}", ex.getMessage());
            }
        }

        return Action.CONTINUE;
    }

    @Override
    public void postInspect(AtmosphereResource r) {		
    }

}
