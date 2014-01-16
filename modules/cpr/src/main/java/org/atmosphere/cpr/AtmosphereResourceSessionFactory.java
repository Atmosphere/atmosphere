package org.atmosphere.cpr;

public abstract class AtmosphereResourceSessionFactory {
	private static final AtmosphereResourceSessionFactory DEFAULT = new DefaultAtmosphereResourceSessionFactory();
	
    public static AtmosphereResourceSessionFactory getDefault() {
        return DEFAULT;
    }
    
    public abstract AtmosphereResourceSession getSession(AtmosphereResource r, boolean create);
    
    public AtmosphereResourceSession getSession(AtmosphereResource r) {
    	return getSession(r, true);
    }
}
