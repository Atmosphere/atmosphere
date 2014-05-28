package org.atmosphere.jersey.gson;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;

import javax.ws.rs.core.MediaType;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;

import org.atmosphere.cpr.Serializer;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * Json Serializer that uses Gson library write json objects.
 * Extend this class and override {@link #initializeGson()} method to setup your own gson configuration.
 * 
 * @author Juan Manuel Musacchio
 */
public class GsonSerializer implements Serializer {

    private MediaType mediaType;
    
    private Gson gson = initializeGson();

    public GsonSerializer(MediaType mediaType) {
        this.mediaType = mediaType;
    }

    public void write(OutputStream os, Object o) throws IOException {
        try {
            if (MediaType.APPLICATION_JSON_TYPE.equals(mediaType)) {
                String str = gson.toJson(o);
                byte[] bytes = str.getBytes(Charset.forName("UTF-8"));
                os.write(bytes);
            } else if (MediaType.APPLICATION_XML_TYPE.equals(mediaType)) {
                JAXBContext jaxbContext = JAXBContext.newInstance(o.getClass());
                jaxbContext.createMarshaller().marshal(o, os);
            } else {
                throw new IOException("Unsupported media type: " + mediaType);
            }
        } catch (JAXBException e) {
            throw new IOException(e);
        }
    }
    
    /**
     * Override this method if you need a different Gson configuration
     * @return
     */
    protected Gson initializeGson() {
        return new GsonBuilder().serializeNulls().create();
    }
}