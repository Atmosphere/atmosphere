package org.atmosphere.samples.lpchat

import java.io.File;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.core.PathSegment;

import org.atmosphere.annotation.Broadcast;
import org.atmosphere.annotation.Suspend;

import scala.collection.jcl.ArrayList;

@Path("/")
class FilesResource {

  @Path("jquery{id}.js")
  @GET
  def getJQuery(@PathParam("id") ps : PathSegment) = new File(ps.getPath());

  @GET
  def getIndex() = new File("index.html");
}
