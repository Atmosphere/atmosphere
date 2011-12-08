package org.atmosphere.util;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import org.atmosphere.config.ApplicationConfig;
import org.atmosphere.config.AtmosphereConfig;
import org.atmosphere.config.AtmosphereHandler;
import org.atmosphere.config.FrameworkConfig;

import com.thoughtworks.xstream.XStream;


public class AtmosphereConfigParser {
	
	private static final AtmosphereConfigParser instance = new AtmosphereConfigParser();
	private XStream xstream = null;
	
	private AtmosphereConfigParser(){
		
	}
	
	public static AtmosphereConfigParser getInstance(){
		return instance;
	}
	
	public AtmosphereConfig parse(String filename) throws FileNotFoundException{
		xstream = new XStream();
		xstream.setMode(XStream.NO_REFERENCES);
		xstream.autodetectAnnotations(true);
		
		xstream.alias("atmosphere-handlers", AtmosphereConfig.class);
		xstream.alias("atmosphere-handler", AtmosphereHandler.class);
		xstream.alias("applicationConfig", ApplicationConfig.class);
		xstream.alias("frameworkConfig", FrameworkConfig.class);
		
		
		AtmosphereConfig config = (AtmosphereConfig)xstream.fromXML(new FileReader(filename));
		
		System.out.println(xstream.toXML(config));
		
		return config;
	}
	
	public AtmosphereConfig parse(InputStream is) throws FileNotFoundException{
		
		AtmosphereConfig config = (AtmosphereConfig)xstream.fromXML(is);
		
		System.out.println(xstream.toXML(config));
		
		return config;
	}
	
	/**
	 * @param args
	 */
	public static void main(String[] args) throws Exception {
		
		AtmosphereConfigParser parser = AtmosphereConfigParser.getInstance();
		
		//parser.output();
		parser.parse("./target/classes/atmosphere.xml");
		

	}

}
