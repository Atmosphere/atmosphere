package org.atmosphere.samples.client;

import com.kfuntak.gwt.json.serialization.client.JsonSerializable;
import java.io.Serializable;
/**
 *
 * @author p.havelaar
 */
public class Event implements JsonSerializable, Serializable {

    long code;
    String data;

    public long getCode() {
        return code;
    }

    public void setCode(long code) {
        this.code = code;
    }

    public String getData() {
        return data;
    }

    public void setData(String data) {
        this.data = data;
    }
    public String toString() {
        return code + ": " + data;
    }
}
