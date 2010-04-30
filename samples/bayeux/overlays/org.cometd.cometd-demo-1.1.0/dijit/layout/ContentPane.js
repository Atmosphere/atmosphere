/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dijit.layout.ContentPane"]){
dojo._hasResource["dijit.layout.ContentPane"]=true;
dojo.provide("dijit.layout.ContentPane");
dojo.require("dijit._Widget");
dojo.require("dijit._Contained");
dojo.require("dijit.layout._LayoutWidget");
dojo.require("dojo.parser");
dojo.require("dojo.string");
dojo.require("dojo.html");
dojo.requireLocalization("dijit","loading",null,"ROOT,ar,ca,cs,da,de,el,es,fi,fr,he,hu,it,ja,ko,nb,nl,pl,pt,pt-pt,ru,sk,sl,sv,th,tr,zh,zh-tw");
dojo.declare("dijit.layout.ContentPane",dijit._Widget,{href:"",extractContent:false,parseOnLoad:true,preventCache:false,preload:false,refreshOnShow:false,loadingMessage:"<span class='dijitContentPaneLoading'>${loadingState}</span>",errorMessage:"<span class='dijitContentPaneError'>${errorState}</span>",isLoaded:false,baseClass:"dijitContentPane",doLayout:true,ioArgs:{},isContainer:true,isLayoutContainer:true,onLoadDeferred:null,attributeMap:dojo.delegate(dijit._Widget.prototype.attributeMap,{title:[]}),postMixInProperties:function(){
this.inherited(arguments);
var _1=dojo.i18n.getLocalization("dijit","loading",this.lang);
this.loadingMessage=dojo.string.substitute(this.loadingMessage,_1);
this.errorMessage=dojo.string.substitute(this.errorMessage,_1);
if(!this.href&&this.srcNodeRef&&this.srcNodeRef.innerHTML){
this.isLoaded=true;
}
},buildRendering:function(){
this.inherited(arguments);
if(!this.containerNode){
this.containerNode=this.domNode;
}
},postCreate:function(){
this.domNode.title="";
if(!dojo.attr(this.domNode,"role")){
dijit.setWaiRole(this.domNode,"group");
}
dojo.addClass(this.domNode,this.baseClass);
},startup:function(){
if(this._started){
return;
}
var _2=dijit._Contained.prototype.getParent.call(this);
this._childOfLayoutWidget=_2&&_2.isLayoutContainer;
this._needLayout=!this._childOfLayoutWidget;
if(this.isLoaded){
dojo.forEach(this.getChildren(),function(_3){
_3.startup();
});
}
if(this._isShown()||this.preload){
this._onShow();
}
this.inherited(arguments);
},_checkIfSingleChild:function(){
var _4=dojo.query("> *",this.containerNode).filter(function(_5){
return _5.tagName!=="SCRIPT";
}),_6=_4.filter(function(_7){
return dojo.hasAttr(_7,"dojoType")||dojo.hasAttr(_7,"widgetId");
}),_8=dojo.filter(_6.map(dijit.byNode),function(_9){
return _9&&_9.domNode&&_9.resize;
});
if(_4.length==_6.length&&_8.length==1){
this._singleChild=_8[0];
}else{
delete this._singleChild;
}
dojo.toggleClass(this.containerNode,this.baseClass+"SingleChild",!!this._singleChild);
},setHref:function(_a){
dojo.deprecated("dijit.layout.ContentPane.setHref() is deprecated. Use attr('href', ...) instead.","","2.0");
return this.attr("href",_a);
},_setHrefAttr:function(_b){
this.cancel();
this.onLoadDeferred=new dojo.Deferred(dojo.hitch(this,"cancel"));
this.href=_b;
if(this._created&&(this.preload||this._isShown())){
this._load();
}else{
this._hrefChanged=true;
}
return this.onLoadDeferred;
},setContent:function(_c){
dojo.deprecated("dijit.layout.ContentPane.setContent() is deprecated.  Use attr('content', ...) instead.","","2.0");
this.attr("content",_c);
},_setContentAttr:function(_d){
this.href="";
this.cancel();
this.onLoadDeferred=new dojo.Deferred(dojo.hitch(this,"cancel"));
this._setContent(_d||"");
this._isDownloaded=false;
return this.onLoadDeferred;
},_getContentAttr:function(){
return this.containerNode.innerHTML;
},cancel:function(){
if(this._xhrDfd&&(this._xhrDfd.fired==-1)){
this._xhrDfd.cancel();
}
delete this._xhrDfd;
this.onLoadDeferred=null;
},uninitialize:function(){
if(this._beingDestroyed){
this.cancel();
}
this.inherited(arguments);
},destroyRecursive:function(_e){
if(this._beingDestroyed){
return;
}
this.inherited(arguments);
},resize:function(_f,_10){
if(!this._wasShown){
this._onShow();
}
this._resizeCalled=true;
if(_f){
dojo.marginBox(this.domNode,_f);
}
var cn=this.containerNode;
if(cn===this.domNode){
var mb=_10||{};
dojo.mixin(mb,_f||{});
if(!("h" in mb)||!("w" in mb)){
mb=dojo.mixin(dojo.marginBox(cn),mb);
}
this._contentBox=dijit.layout.marginBox2contentBox(cn,mb);
}else{
this._contentBox=dojo.contentBox(cn);
}
this._layoutChildren();
},_isShown:function(){
if(this._childOfLayoutWidget){
if(this._resizeCalled&&"open" in this){
return this.open;
}
return this._resizeCalled;
}else{
if("open" in this){
return this.open;
}else{
var _11=this.domNode;
return (_11.style.display!="none")&&(_11.style.visibility!="hidden")&&!dojo.hasClass(_11,"dijitHidden");
}
}
},_onShow:function(){
if(this.href){
if(!this._xhrDfd&&(!this.isLoaded||this._hrefChanged||this.refreshOnShow)){
this.refresh();
}
}else{
if(!this._childOfLayoutWidget&&this._needLayout){
this._layoutChildren();
}
}
this.inherited(arguments);
this._wasShown=true;
},refresh:function(){
this.cancel();
this.onLoadDeferred=new dojo.Deferred(dojo.hitch(this,"cancel"));
this._load();
return this.onLoadDeferred;
},_load:function(){
this._setContent(this.onDownloadStart(),true);
var _12=this;
var _13={preventCache:(this.preventCache||this.refreshOnShow),url:this.href,handleAs:"text"};
if(dojo.isObject(this.ioArgs)){
dojo.mixin(_13,this.ioArgs);
}
var _14=(this._xhrDfd=(this.ioMethod||dojo.xhrGet)(_13));
_14.addCallback(function(_15){
try{
_12._isDownloaded=true;
_12._setContent(_15,false);
_12.onDownloadEnd();
}
catch(err){
_12._onError("Content",err);
}
delete _12._xhrDfd;
return _15;
});
_14.addErrback(function(err){
if(!_14.canceled){
_12._onError("Download",err);
}
delete _12._xhrDfd;
return err;
});
delete this._hrefChanged;
},_onLoadHandler:function(_16){
this.isLoaded=true;
try{
this.onLoadDeferred.callback(_16);
this.onLoad(_16);
}
catch(e){
console.error("Error "+this.widgetId+" running custom onLoad code: "+e.message);
}
},_onUnloadHandler:function(){
this.isLoaded=false;
try{
this.onUnload();
}
catch(e){
console.error("Error "+this.widgetId+" running custom onUnload code: "+e.message);
}
},destroyDescendants:function(){
if(this.isLoaded){
this._onUnloadHandler();
}
var _17=this._contentSetter;
dojo.forEach(this.getChildren(),function(_18){
if(_18.destroyRecursive){
_18.destroyRecursive();
}
});
if(_17){
dojo.forEach(_17.parseResults,function(_19){
if(_19.destroyRecursive&&_19.domNode&&_19.domNode.parentNode==dojo.body()){
_19.destroyRecursive();
}
});
delete _17.parseResults;
}
dojo.html._emptyNode(this.containerNode);
delete this._singleChild;
},_setContent:function(_1a,_1b){
this.destroyDescendants();
var _1c=this._contentSetter;
if(!(_1c&&_1c instanceof dojo.html._ContentSetter)){
_1c=this._contentSetter=new dojo.html._ContentSetter({node:this.containerNode,_onError:dojo.hitch(this,this._onError),onContentError:dojo.hitch(this,function(e){
var _1d=this.onContentError(e);
try{
this.containerNode.innerHTML=_1d;
}
catch(e){
console.error("Fatal "+this.id+" could not change content due to "+e.message,e);
}
})});
}
var _1e=dojo.mixin({cleanContent:this.cleanContent,extractContent:this.extractContent,parseContent:this.parseOnLoad},this._contentSetterParams||{});
dojo.mixin(_1c,_1e);
_1c.set((dojo.isObject(_1a)&&_1a.domNode)?_1a.domNode:_1a);
delete this._contentSetterParams;
if(!_1b){
dojo.forEach(this.getChildren(),function(_1f){
if(!this.parseOnLoad||_1f.getParent){
_1f.startup();
}
},this);
this._scheduleLayout();
this._onLoadHandler(_1a);
}
},_onError:function(_20,err,_21){
this.onLoadDeferred.errback(err);
var _22=this["on"+_20+"Error"].call(this,err);
if(_21){
console.error(_21,err);
}else{
if(_22){
this._setContent(_22,true);
}
}
},_scheduleLayout:function(){
if(this._isShown()){
this._layoutChildren();
}else{
this._needLayout=true;
}
},_layoutChildren:function(){
if(this.doLayout){
this._checkIfSingleChild();
}
if(this._singleChild&&this._singleChild.resize){
var cb=this._contentBox||dojo.contentBox(this.containerNode);
this._singleChild.resize({w:cb.w,h:cb.h});
}else{
dojo.forEach(this.getChildren(),function(_23){
if(_23.resize){
_23.resize();
}
});
}
delete this._needLayout;
},onLoad:function(_24){
},onUnload:function(){
},onDownloadStart:function(){
return this.loadingMessage;
},onContentError:function(_25){
},onDownloadError:function(_26){
return this.errorMessage;
},onDownloadEnd:function(){
}});
}
