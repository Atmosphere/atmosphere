package app;

import java.util.List;
import java.util.LinkedList;

import org.apache.struts.actions.DispatchAction;
import org.apache.struts.action.ActionForm;
import org.apache.struts.action.ActionForward;
import org.apache.struts.action.ActionMapping;

import org.apache.log4j.Logger;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.atmosphere.cpr.BroadcastFilter;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.DefaultBroadcaster;
import org.atmosphere.cpr.Meteor;

public class SimpleAction extends DispatchAction {
	public static final Logger logger = Logger.getLogger(SimpleAction.class);
	private final List<BroadcastFilter> list;
	private final Broadcaster b = new DefaultBroadcaster("Struts"); 
	
	public SimpleAction (){
	 list = new LinkedList<BroadcastFilter>();
	 //list.add(new XSSHtmlFilter());
	 //list.add(new JsonpFilter());
} 

	public ActionForward unspecified(ActionMapping mapping, ActionForm _form, HttpServletRequest req, HttpServletResponse res) throws Exception {
		logger.error("IN ACTION");
		return mapping.findForward("success");
	}
	
	public ActionForward echo(ActionMapping mapping, ActionForm _form, HttpServletRequest req, HttpServletResponse res) throws Exception {
		logger.info("BEGIN SimpleAction.echo()");
		String value = req.getParameter("value");
		// Do something with value
		res.getWriter().print("{message: 'Server says: "+value+"'}");
		return null;
	}
	
	public ActionForward openCometChannel(ActionMapping mapping, ActionForm _form, HttpServletRequest req, HttpServletResponse res) throws Exception {
		logger.info("BEGIN SimpleAction.openCometChannel()");
	  Meteor m = Meteor.build(req, list, null);
	  m.setBroadcaster(b); 
		req.getSession().setAttribute("meteor", m);
		m.suspend(-1);
		m.broadcast(req.getServerName()
			+ "__has suspended a connection from " + req.getRemoteAddr());
		return null;
	}
	
	public ActionForward sendCometMsg(ActionMapping mapping, ActionForm _form, HttpServletRequest req, HttpServletResponse res) throws Exception {
		logger.info("BEGIN SimpleAction.sendCometMsg()");
	  Meteor m = (Meteor)req.getSession().getAttribute("meteor");
	  logger.info("meteor: " + m);
		res.setCharacterEncoding("UTF-8");
		String value = req.getParameter("value");
		logger.debug("value: " + value);
		
		m.broadcast("<script>parent.cometMsg('Broadcast: " + value + "');</script>");
		res.getWriter().write("{message:'success'}");
		res.getWriter().flush();
		return null;
  }
}
