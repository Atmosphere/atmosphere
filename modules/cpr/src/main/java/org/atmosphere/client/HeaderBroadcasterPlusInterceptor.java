package org.atmosphere.client;

import static org.atmosphere.cpr.ApplicationConfig.PROPERTY_USE_STREAM;

import java.io.UnsupportedEncodingException;

import org.atmosphere.cache.HeaderBroadcasterCachePlus;
import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AsyncIOInterceptorAdapter;
import org.atmosphere.cpr.AsyncIOWriter;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereInterceptorAdapter;
import org.atmosphere.cpr.AtmosphereInterceptorWriter;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEventImpl;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HeaderBroadcasterPlusInterceptor extends AtmosphereInterceptorAdapter {

    private static final Logger logger = LoggerFactory.getLogger(HeaderBroadcasterPlusInterceptor.class);

    private final static byte[] END = "##".getBytes();
    private byte[] end = END;
    private String endString = "##";

    @Override
    public void configure(AtmosphereConfig config) {
        String s = config.getInitParameter(ApplicationConfig.MESSAGE_TIMESTAMP_DELIMITER);
        if (s != null) {
            end = s.getBytes();
            endString = s;
        }
    }

    @Override
    public Action inspect(final AtmosphereResource r) {
        final AtmosphereResponse response = r.getResponse();

        super.inspect(r);

        AsyncIOWriter writer = response.getAsyncIOWriter();
        if (AtmosphereInterceptorWriter.class.isAssignableFrom(writer.getClass())) {
            AtmosphereInterceptorWriter.class.cast(writer).interceptor(new AsyncIOInterceptorAdapter() {

                @Override
                public void postPayload(AtmosphereResponse response, byte[] data, int offset, int length) {
                	String message = new String(data);
                	boolean isUsingStream = (Boolean) response.request().getAttribute(PROPERTY_USE_STREAM);
                	if(isUsingStream) {
                		try {
							message = new String(data, response.getCharacterEncoding());
						} catch (UnsupportedEncodingException e) {
					        AtmosphereResource r = response.resource();
					        if (r != null) {
					            AtmosphereResourceImpl.class.cast(r).notifyListeners(
					                    new AtmosphereResourceEventImpl(AtmosphereResourceImpl.class.cast(r), true, false));
					        }
					        logger.trace("", e);
					        return;
						}
                	}
                	
                	HeaderBroadcasterCachePlus.readWriteLockForMessageCachedTimes.writeLock().lock();
                    try {
                    	String timestamp = Long.toString(HeaderBroadcasterCachePlus.messageCachedTimes.get(message));
                    	timestamp = new String(end) + timestamp;
                        response.write(timestamp.getBytes());
                        
                        Boolean flag = HeaderBroadcasterCachePlus.messageCachedTimesFlag.get(message);
                        if(flag!=null && flag) {
                        	HeaderBroadcasterCachePlus.messageCachedTimesFlag.put(message.toString(), false);
                        }
                    } finally {
                    	HeaderBroadcasterCachePlus.readWriteLockForMessageCachedTimes.writeLock().unlock();
                    }

                    
                }

            });
        } else {
            logger.warn("Unable to apply {}. Your AsyncIOWriter must implement {}", getClass().getName(), AtmosphereInterceptorWriter.class.getName());
        }
        return Action.CONTINUE;
    }

    @Override
    public String toString() {
        return endString + " Header Broadcaster Plus Interceptor";
    }
}