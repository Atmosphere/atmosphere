/*
 * Copyright 2012 Sebastien Dionne
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.atmosphere.util;

import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.InputStream;

import org.atmosphere.config.ApplicationConfig;
import org.atmosphere.config.AtmosphereConfig;
import org.atmosphere.config.AtmosphereHandler;
import org.atmosphere.config.FrameworkConfig;

import com.thoughtworks.xstream.XStream;

/**
 * 
 * @author Sebastien Dionne  : sebastien.dionne@gmail.com
 *
 */
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
	
}
