package org.atmosphere.config;

import com.thoughtworks.xstream.annotations.XStreamAlias;

@XStreamAlias("frameworkConfig")
public class FrameworkConfig {
	
	@XStreamAlias("param-name")
	private String paramName;
	
	@XStreamAlias("param-value")
	private String paramValue;
	
	public FrameworkConfig(String paramName, String paramValue){
		this.paramName = paramName;
		this.paramValue = paramValue;
	}
	
	public String getParamName() {
		return paramName;
	}
	public void setParamName(String paramName) {
		this.paramName = paramName;
	}
	public String getParamValue() {
		return paramValue;
	}
	public void setParamValue(String paramValue) {
		this.paramValue = paramValue;
	}

}
