package org.atmosphere.interceptor;

import java.util.concurrent.atomic.AtomicReference;

import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereInterceptor;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResponse;

/**
 * CORS support
 *
 * @author Janusz Sobolewski
 */
public class CorsInterceptor implements AtmosphereInterceptor {
    
    @Override
    public void configure(AtmosphereConfig arg0) {
    }
    
    @Override
    public Action inspect(AtmosphereResource resource) {
        
        AtmosphereRequest req = resource.getRequest();                                   
        AtmosphereResponse res = resource.getResponse();
        
        if(req.getHeader("Origin") != null){
            res.addHeader("Access-Control-Allow-Origin", req.getHeader("Origin"));
            res.addHeader("Access-Control-Expose-Headers", "X-Cache-Date, X-Atmosphere-tracking-id");
            res.setHeader("Access-Control-Allow-Credentials", "true");
        }

        if("OPTIONS".equals(req.getMethod())){
            res.setHeader("Access-Control-Allow-Methods", "OPTIONS, GET, POST");
            res.setHeader("Access-Control-Allow-Headers",
                    "Origin, Content-Type, X-Atmosphere-Framework, X-Cache-Date, X-Atmosphere-tracking-id, X-Atmosphere-Transport");
            res.setHeader("Access-Control-Max-Age", "-1");
            
            final AtomicReference<String> emptyMessage = new AtomicReference<String>("");
            res.write(emptyMessage.get());
            
            return Action.SKIP_ATMOSPHEREHANDLER;
        } 
        
        return Action.CONTINUE;
    }

    @Override
    public void postInspect(AtmosphereResource resource) {
        
    }

}