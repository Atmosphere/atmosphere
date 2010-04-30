/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dijit._editor.plugins.ViewSource"]){
dojo._hasResource["dijit._editor.plugins.ViewSource"]=true;
dojo.provide("dijit._editor.plugins.ViewSource");
dojo.require("dijit._editor._Plugin");
dojo.require("dijit.form.Button");
dojo.require("dojo.i18n");
dojo.requireLocalization("dijit._editor","commands",null,"ROOT,ar,ca,cs,da,de,el,es,fi,fr,he,hu,it,ja,ko,nb,nl,pl,pt,pt-pt,ru,sk,sl,sv,th,tr,zh,zh-tw");
dojo.declare("dijit._editor.plugins.ViewSource",dijit._editor._Plugin,{stripScripts:true,stripComments:true,stripIFrames:true,readOnly:false,_fsPlugin:null,toggle:function(){
if(dojo.isWebKit){
this._vsFocused=true;
}
this.button.attr("checked",!this.button.attr("checked"));
},_initButton:function(){
var _1=dojo.i18n.getLocalization("dijit._editor","commands");
this.button=new dijit.form.ToggleButton({label:_1["viewSource"],showLabel:false,iconClass:this.iconClassPrefix+" "+this.iconClassPrefix+"ViewSource",tabIndex:"-1",onChange:dojo.hitch(this,"_showSource")});
if(dojo.isIE==7){
this._ieFixNode=dojo.create("div",{style:{opacity:"0",zIndex:"-1000",position:"absolute",top:"-1000px"}},dojo.body());
}
this.button.attr("readOnly",false);
},setEditor:function(_2){
this.editor=_2;
this._initButton();
this.editor.addKeyHandler(dojo.keys.F12,true,true,dojo.hitch(this,function(e){
this.button.focus();
this.toggle();
dojo.stopEvent(e);
setTimeout(dojo.hitch(this,function(){
this.editor.focus();
}),100);
}));
},_showSource:function(_3){
var ed=this.editor;
var _4=ed._plugins;
var _5;
this._sourceShown=_3;
var _6=this;
try{
if(!this.sourceArea){
this._createSourceView();
}
if(_3){
ed._sourceQueryCommandEnabled=ed.queryCommandEnabled;
ed.queryCommandEnabled=function(_7){
var _8=_7.toLowerCase();
if(_8==="viewsource"){
return true;
}else{
return false;
}
};
this.editor.onDisplayChanged();
_5=ed.attr("value");
_5=this._filter(_5);
ed.attr("value",_5);
this._pluginList=[];
this._disabledPlugins=dojo.filter(_4,function(p){
if(p&&p.button&&!p.button.attr("disabled")&&!(p instanceof dijit._editor.plugins.ViewSource)){
p._vs_updateState=p.updateState;
p.updateState=function(){
return false;
};
p.button.attr("disabled",true);
if(p.command){
switch(p.command){
case "bold":
case "italic":
case "underline":
case "strikethrough":
case "superscript":
case "subscript":
p.button.attr("checked",false);
break;
default:
break;
}
}
return true;
}
});
if(this._fsPlugin){
this._fsPlugin._getAltViewNode=function(){
return _6.sourceArea;
};
}
this.sourceArea.value=_5;
var is=dojo.marginBox(ed.iframe.parentNode);
dojo.marginBox(this.sourceArea,{w:is.w,h:is.h});
dojo.style(ed.iframe,"display","none");
dojo.style(this.sourceArea,{display:"block"});
var _9=function(){
var vp=dijit.getViewport();
if("_prevW" in this&&"_prevH" in this){
if(vp.w===this._prevW&&vp.h===this._prevH){
return;
}else{
this._prevW=vp.w;
this._prevH=vp.h;
}
}else{
this._prevW=vp.w;
this._prevH=vp.h;
}
if(this._resizer){
clearTimeout(this._resizer);
delete this._resizer;
}
this._resizer=setTimeout(dojo.hitch(this,function(){
delete this._resizer;
this._resize();
}),10);
};
this._resizeHandle=dojo.connect(window,"onresize",this,_9);
setTimeout(dojo.hitch(this,this._resize),100);
this.editor.onNormalizedDisplayChanged();
}else{
if(!ed._sourceQueryCommandEnabled){
return;
}
dojo.disconnect(this._resizeHandle);
delete this._resizeHandle;
ed.queryCommandEnabled=ed._sourceQueryCommandEnabled;
if(!this._readOnly){
_5=this.sourceArea.value;
_5=this._filter(_5);
ed.attr("value",_5);
}
dojo.forEach(this._disabledPlugins,function(p){
p.button.attr("disabled",false);
if(p._vs_updateState){
p.updateState=p._vs_updateState;
}
});
this._disabledPlugins=null;
dojo.style(this.sourceArea,"display","none");
dojo.style(ed.iframe,"display","block");
delete ed._sourceQueryCommandEnabled;
this.editor.onDisplayChanged();
}
}
catch(e){
}
},_resize:function(){
var ed=this.editor;
var tb=dojo.position(ed.toolbar.domNode);
var eb=dojo.position(ed.domNode);
var _a=dojo._getPadBorderExtents(ed.domNode);
var _b={w:eb.w-_a.w,h:eb.h-(tb.h+_a.h)};
if(this._fsPlugin&&this._fsPlugin.isFullscreen){
var vp=dijit.getViewport();
_b.w=(vp.w-_a.w);
_b.h=(vp.h-(tb.h+_a.h));
}
if(dojo.isIE){
_b.h-=2;
}
if(this._ieFixNode){
var _c=-this._ieFixNode.offsetTop/1000;
_b.w=Math.floor((_b.w+0.9)/_c);
_b.h=Math.floor((_b.h+0.9)/_c);
}
dojo.marginBox(this.sourceArea,{w:_b.w,h:_b.h});
},_createSourceView:function(){
var ed=this.editor;
var _d=ed._plugins;
this.sourceArea=dojo.create("textarea");
if(this.readOnly){
dojo.attr(this.sourceArea,"readOnly",true);
this._readOnly=true;
}
dojo.style(this.sourceArea,{padding:"0px",margin:"0px",borderWidth:"0px",borderStyle:"none"});
dojo.place(this.sourceArea,ed.iframe,"before");
dojo.style(this.sourceArea.parentNode,{padding:"0px",margin:"0px",borderWidth:"0px",borderStyle:"none"});
if(dojo.isIE&&ed.iframe.parentNode.lastChild!==ed.iframe){
dojo.style(ed.iframe.parentNode.lastChild,{width:"0px",height:"0px",padding:"0px",margin:"0px",borderWidth:"0px",borderStyle:"none"});
}
ed._viewsource_oldFocus=ed.focus;
var _e=this;
ed.focus=function(){
if(_e._sourceShown){
_e.setSourceAreaCaret();
}else{
try{
if(this._vsFocused){
delete this._vsFocused;
dijit.focus(ed.editNode);
}else{
ed._viewsource_oldFocus();
}
}
catch(e){
}
}
};
var i,p;
for(i=0;i<_d.length;i++){
p=_d[i];
if(p&&(p.declaredClass==="dijit._editor.plugins.FullScreen"||p.declaredClass===(dijit._scopeName+"._editor.plugins.FullScreen"))){
this._fsPlugin=p;
break;
}
}
if(this._fsPlugin){
this._fsPlugin._viewsource_getAltViewNode=this._fsPlugin._getAltViewNode;
this._fsPlugin._getAltViewNode=function(){
return _e._sourceShown?_e.sourceArea:this._viewsource_getAltViewNode();
};
}
this.connect(this.sourceArea,"onkeydown",dojo.hitch(this,function(e){
if(this._sourceShown&&e.keyCode==dojo.keys.F12&&e.ctrlKey&&e.shiftKey){
this.button.focus();
this.button.attr("checked",false);
setTimeout(dojo.hitch(this,function(){
ed.focus();
}),100);
dojo.stopEvent(e);
}
}));
},_stripScripts:function(_f){
if(_f){
_f=_f.replace(/<\s*script[^>]*>((.|\s)*?)<\\?\/\s*script\s*>/ig,"");
_f=_f.replace(/<\s*script\b([^<>]|\s)*>?/ig,"");
_f=_f.replace(/<[^>]*=(\s|)*[("|')]javascript:[^$1][(\s|.)]*[$1][^>]*>/ig,"");
}
return _f;
},_stripComments:function(_10){
if(_10){
_10=_10.replace(/<!--(.|\s){1,}?-->/g,"");
}
return _10;
},_stripIFrames:function(_11){
if(_11){
_11=_11.replace(/<\s*iframe[^>]*>((.|\s)*?)<\\?\/\s*iframe\s*>/ig,"");
}
return _11;
},_filter:function(_12){
if(_12){
if(this.stripScripts){
_12=this._stripScripts(_12);
}
if(this.stripComments){
_12=this._stripComments(_12);
}
if(this.stripIFrames){
_12=this._stripIFrames(_12);
}
}
return _12;
},setSourceAreaCaret:function(){
var win=dojo.global;
var _13=this.sourceArea;
dijit.focus(_13);
if(this._sourceShown&&!this.readOnly){
if(dojo.isIE){
if(this.sourceArea.createTextRange){
var _14=_13.createTextRange();
_14.collapse(true);
_14.moveStart("character",-99999);
_14.moveStart("character",0);
_14.moveEnd("character",0);
_14.select();
}
}else{
if(win.getSelection){
if(_13.setSelectionRange){
_13.setSelectionRange(0,0);
}
}
}
}
},destroy:function(){
if(this._ieFixNode){
dojo.body().removeChild(this._ieFixNode);
}
if(this._resizer){
clearTimeout(this._resizer);
delete this._resizer;
}
if(this._resizeHandle){
dojo.disconnect(this._resizeHandle);
delete this._resizeHandle;
}
this.inherited(arguments);
}});
dojo.subscribe(dijit._scopeName+".Editor.getPlugin",null,function(o){
if(o.plugin){
return;
}
var _15=o.args.name.toLowerCase();
if(_15==="viewsource"){
o.plugin=new dijit._editor.plugins.ViewSource({readOnly:("readOnly" in o.args)?o.args.readOnly:false,stripComments:("stripComments" in o.args)?o.args.stripComments:true,stripScripts:("stripScripts" in o.args)?o.args.stripScripts:true,stripIFrames:("stripIFrames" in o.args)?o.args.stripIFrames:true});
}
});
}
