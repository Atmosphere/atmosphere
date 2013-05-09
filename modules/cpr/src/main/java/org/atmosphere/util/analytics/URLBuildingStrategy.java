package org.atmosphere.util.analytics;

/**
 * Fork of https://code.google.com/p/jgoogleanalytics/
 * Interface for the URL building strategy
 *
 * @author : Siddique Hameed
 * @version : 0.1
 */
public interface URLBuildingStrategy {
    public String buildURL(FocusPoint focusPoint);

    public void setRefererURL(String refererURL);
}
