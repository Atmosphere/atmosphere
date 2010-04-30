package org.atmosphere.samples.lpchat

import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;

import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.ext.MessageBodyWriter;
import javax.ws.rs.ext.Provider;

import org.codehaus.jackson.jaxrs.JacksonJsonProvider;

@Provider
@Produces(Array("*/*"))
class ListProvider extends JacksonJsonProvider {

  private val arrayListClass = classOf[java.util.ArrayList[_]];

  override def isWriteable(c: Class[_],
                           gt: Type,
                           annotations: Array[Annotation],
                           mediaType: MediaType) : boolean = {
    classOf[List[_]].isAssignableFrom(c) &&
        super.isWriteable(arrayListClass, arrayListClass,
                          annotations, MediaType.APPLICATION_JSON_TYPE);
  }

  override def writeTo(t: Object,
                       c: Class[_],
                       gt: Type,
                       annotations: Array[Annotation],
                       mediaType: MediaType,
                       httpHeaders: MultivaluedMap[String, Object],
                       entityStream: OutputStream) : unit = {

    val l = t.asInstanceOf[List[_]];
    val al = new java.util.ArrayList[Any]();
    for (m <- l) {
      al.add(m);
    }

    super.writeTo(al, arrayListClass, arrayListClass,
                  annotations, mediaType, httpHeaders, entityStream);
  }
}
