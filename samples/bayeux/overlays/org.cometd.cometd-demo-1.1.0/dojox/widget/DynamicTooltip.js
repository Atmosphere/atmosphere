/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dojox.widget.DynamicTooltip"]){
dojo._hasResource["dojox.widget.DynamicTooltip"]=true;
dojo.provide("dojox.widget.DynamicTooltip");
dojo.experimental("dojox.widget.DynamicTooltip");
dojo.require("dijit.Tooltip");
dojo.requireLocalization("dijit","loading",null,"ROOT,ar,ca,cs,da,de,el,es,fi,fr,he,hu,it,ja,ko,nb,nl,pl,pt,pt-pt,ru,sk,sl,sv,th,tr,zh,zh-tw");
dojo.declare("dojox.widget.DynamicTooltip",dijit.Tooltip,{hasLoaded:false,href:"",label:"",preventCache:false,postMixInProperties:function(){
this.inherited(arguments);
this._setLoadingLabel();
},_setLoadingLabel:function(){
if(this.href){
this.label=dojo.i18n.getLocalization("dijit","loading",this.lang).loadingState;
}
},_setHrefAttr:function(_1){
this.href=_1;
this.hasLoaded=false;
},loadContent:function(){
if(!this.hasLoaded&&this.href){
this._setLoadingLabel();
this.hasLoaded=true;
dojo.xhrGet({url:this.href,handleAs:"text",tooltipWidget:this,load:function(_2,_3){
this.tooltipWidget.label=_2;
this.tooltipWidget.close();
this.tooltipWidget.open();
},preventCache:this.preventCache});
}
},refresh:function(){
this.hasLoaded=false;
},open:function(_4){
_4=_4||this._connectNodes[0];
if(!_4){
return;
}
this.loadContent();
this.inherited(arguments);
}});
}
