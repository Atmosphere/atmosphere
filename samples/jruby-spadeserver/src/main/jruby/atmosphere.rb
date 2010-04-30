require 'java'

Dir["./*.jar"].each { |jar| require jar }

include_class 'javax.servlet.http.HttpServlet'
include_class 'org.atmosphere.cpr.AtmosphereHandler'
include_class 'org.atmosphere.grizzly.AtmosphereSpadeServer'

#setup and start the server
def main
  AtmosphereSpadeServer.build("http://localhost:8080/").addAtmosphereHandler("", ChatPage.new()).addAtmosphereHandler("/chat-stream", ChatStream.new()).start
end


#serve the chat page
class ChatPage
  include AtmosphereHandler

  def onRequest(event)
    Kernel.load(__FILE__)
    res = event.getResponse()

    res.setContentType("text/html")
    res.addHeader("Cache-Control", "private")
    res.addHeader("Pragma", "no-cache")
    File.open(File.dirname(__FILE__)+'/chat.html').each { |line|
      res.getWriter().write(line)
    }
    res.getWriter().flush()
    event
  end
end

#serve the chat stream and post messages
class ChatStream
  include AtmosphereHandler

  BEGIN_SCRIPT_TAG = '<script>'
  END_SCRIPT_TAG = '</script>'

  def onRequest(event)
    #reload the source so we can work on it
    #Kernel.load(__FILE__)
    req = event.getRequest();
    res = event.getResponse();

    res.setContentType("text/html")
    res.addHeader("Cache-Control", "private")
    res.addHeader("Pragma", "no-cache")
    res.setCharacterEncoding("UTF-8");

    if (req.getMethod().upcase === "GET")
      # for IE
      res.getWriter().write("<!-- Comet is a programming technique that enables web " +
              "servers to send data to the client without having any need " +
              "for the client to request it. -->\n");
      res.getWriter().flush();
      event.suspend();

    elsif (req.getMethod().upcase === "POST")
      message = req.getParameterValues("message")[0].to_s
      ip = req.getRemoteAddr().to_s
      #if (rand()*3).to_i == 2
      #  message = message.gsub(/[^aeiou]/, '*')
      #end

        event.getBroadcaster().broadcast(
          BEGIN_SCRIPT_TAG +
            "window.parent.say('<small>#{ip} - #{req.getHeader("User-Agent").to_s}:</small><br><b>#{message}</b>')" +
          END_SCRIPT_TAG);

        res.getWriter().write('success')
        res.getWriter().flush();
    end
  end

  def onStateChange(resourceEvent)
      writer = resourceEvent.getResource().getResponse().getWriter()
      writer.write(resourceEvent.getMessage().to_s.ljust(1*1024))
      writer.flush();
  end
end



if !$once
  main
  $once = true
end


