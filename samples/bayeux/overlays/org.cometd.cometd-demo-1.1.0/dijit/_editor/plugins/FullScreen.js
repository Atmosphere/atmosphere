/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dijit._editor.plugins.FullScreen"]){
dojo._hasResource["dijit._editor.plugins.FullScreen"]=true;
dojo.provide("dijit._editor.plugins.FullScreen");
dojo.require("dijit._editor._Plugin");
dojo.require("dijit.form.Button");
dojo.require("dojo.i18n");
dojo.requireLocalization("dijit._editor","commands",null,"ROOT,ar,ca,cs,da,de,el,es,fi,fr,he,hu,it,ja,ko,nb,nl,pl,pt,pt-pt,ru,sk,sl,sv,th,tr,zh,zh-tw");
dojo.declare("dijit._editor.plugins.FullScreen",dijit._editor._Plugin,{zIndex:500,_origState:null,_origiFrameState:null,_resizeHandle:null,isFullscreen:false,toggle:function(){
this.button.attr("checked",!this.button.attr("checked"));
},_initButton:function(){
var _1=dojo.i18n.getLocalization("dijit._editor","commands");
this.button=new dijit.form.ToggleButton({label:_1["fullScreen"],showLabel:false,iconClass:this.iconClassPrefix+" "+this.iconClassPrefix+"FullScreen",tabIndex:"-1",onChange:dojo.hitch(this,"_setFullScreen")});
},setEditor:function(_2){
this.editor=_2;
this._initButton();
this.editor.addKeyHandler(dojo.keys.F11,true,true,dojo.hitch(this,function(e){
this.toggle();
dojo.stopEvent(e);
setTimeout(dojo.hitch(this,function(){
this.editor.focus();
}),250);
return true;
}));
this.connect(this.editor.domNode,"onkeydown","_containFocus");
},_containFocus:function(e){
if(this.isFullscreen){
var ed=this.editor;
if(!ed.isTabIndent&&ed._fullscreen_oldOnKeyDown&&e.keyCode===dojo.keys.TAB){
var f=dijit.getFocus();
var _3=this._getAltViewNode();
if(f.node==ed.iframe||(_3&&f.node===_3)){
setTimeout(dojo.hitch(this,function(){
ed.toolbar.focus();
}),10);
}else{
if(_3&&dojo.style(ed.iframe,"display")==="none"){
setTimeout(dojo.hitch(this,function(){
dijit.focus(_3);
}),10);
}else{
setTimeout(dojo.hitch(this,function(){
ed.focus();
}),10);
}
}
dojo.stopEvent(e);
}else{
if(ed._fullscreen_oldOnKeyDown){
ed._fullscreen_oldOnKeyDown(e);
}
}
}
},_resizeEditor:function(){
var vp=dijit.getViewport();
dojo.marginBox(this.editor.domNode,{w:vp.w,h:vp.h});
var _4=dojo.marginBox(this.editor.toolbar.domNode);
var _5=dojo._getPadBorderExtents(this.editor.domNode);
var _6=vp.h-(_4.h+_5.h);
dojo.marginBox(this.editor.iframe.parentNode,{h:_6});
dojo.marginBox(this.editor.iframe,{h:_6});
},_getAltViewNode:function(){
},_setFullScreen:function(_7){
var vp=dijit.getViewport();
var ed=this.editor;
var _8=dojo.body();
var _9=ed.domNode.parentNode;
this.isFullscreen=_7;
if(_7){
while(_9&&_9!==dojo.body()){
dojo.addClass(_9,"dijitForceStatic");
_9=_9.parentNode;
}
this._editorResizeHolder=this.editor.resize;
ed.resize=function(){
};
ed._fullscreen_oldOnKeyDown=ed.onKeyDown;
ed.onKeyDown=dojo.hitch(this,this._containFocus);
this._origState={};
this._origiFrameState={};
var _a=ed.domNode,_b=_a&&_a.style||{};
this._origState={width:_b.width||"",height:_b.height||"",top:dojo.style(_a,"top")||"",left:dojo.style(_a,"left")||"",position:dojo.style(_a,"position")||"static"};
var _c=ed.iframe,_d=_c&&_c.style||{};
var bc=dojo.style(ed.iframe,"backgroundColor");
this._origiFrameState={backgroundColor:bc||"transparent",width:_d.width||"auto",height:_d.height||"auto",zIndex:_d.zIndex||""};
dojo.style(ed.domNode,{position:"absolute",top:"0px",left:"0px",zIndex:this.zIndex,width:vp.w+"px",height:vp.h+"px"});
dojo.style(ed.iframe,{height:"100%",width:"100%",zIndex:this.zIndex,backgroundColor:bc!=="transparent"&&bc!=="rgba(0, 0, 0, 0)"?bc:"white"});
dojo.style(ed.iframe.parentNode,{height:"95%",width:"100%"});
if(_8.style&&_8.style.overflow){
this._oldOverflow=dojo.style(_8,"overflow");
}else{
this._oldOverflow="";
}
if(dojo.isIE&&!dojo.isQuirks){
if(_8.parentNode&&_8.parentNode.style&&_8.parentNode.style.overflow){
this._oldBodyParentOverflow=_8.parentNode.style.overflow;
}else{
this._oldBodyParentOverflow="scroll";
}
dojo.style(_8.parentNode,"overflow","hidden");
}
dojo.style(_8,"overflow","hidden");
var _e=function(){
var vp=dijit.getViewport();
if("_prevW" in this&&"_prevH" in this){
if(vp.w===this._prevW&&vp.h===this._prevH){
return;
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
this._resizeEditor();
}),10);
};
this._resizeHandle=dojo.connect(window,"onresize",this,_e);
this._resizeEditor();
var dn=this.editor.toolbar.domNode;
setTimeout(function(){
dijit.scrollIntoView(dn);
},250);
}else{
if(this._editorResizeHolder){
this.editor.resize=this._editorResizeHolder;
}
if(!this._origState&&!this._origiFrameState){
return;
}
if(ed._fullscreen_oldOnKeyDown){
ed.onKeyDown=ed._fullscreen_oldOnKeyDown;
delete ed._fullscreen_oldOnKeyDown;
}
if(this._resizeHandle){
dojo.disconnect(this._resizeHandle);
this._resizeHandle=null;
}
if(this._rst){
clearTimeout(this._rst);
this._rst=null;
}
while(_9&&_9!==dojo.body()){
dojo.removeClass(_9,"dijitForceStatic");
_9=_9.parentNode;
}
var _f=this;
setTimeout(function(){
if(dojo.isIE&&!dojo.isQuirks){
_8.parentNode.style.overflow=_f._oldBodyParentOverflow;
delete _f._oldBodyParentOverflow;
}
dojo.style(_8,"overflow",_f._oldOverflow);
delete _f._oldOverflow;
dojo.style(ed.domNode,_f._origState);
dojo.style(ed.iframe.parentNode,{height:"",width:""});
dojo.style(ed.iframe,_f._origiFrameState);
delete _f._origState;
delete _f._origiFrameState;
ed.resize();
var _10=dijit.getEnclosingWidget(ed.domNode.parentNode);
if(_10&&_10.resize){
_10.resize();
}
dijit.scrollIntoView(_f.editor.toolbar.domNode);
},100);
}
},destroy:function(){
if(this._resizeHandle){
dojo.disconnect(this._resizeHandle);
this._resizeHandle=null;
}
if(this._resizer){
clearTimeout(this._resizer);
this._resizer=null;
}
this.inherited(arguments);
}});
dojo.subscribe(dijit._scopeName+".Editor.getPlugin",null,function(o){
if(o.plugin){
return;
}
var _11=o.args.name.toLowerCase();
if(_11==="fullscreen"){
o.plugin=new dijit._editor.plugins.FullScreen({zIndex:("zIndex" in o.args)?o.args.zIndex:500});
}
});
}
