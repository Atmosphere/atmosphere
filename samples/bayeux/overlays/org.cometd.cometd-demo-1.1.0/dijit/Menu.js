/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dijit.Menu"]){
dojo._hasResource["dijit.Menu"]=true;
dojo.provide("dijit.Menu");
dojo.require("dijit._Widget");
dojo.require("dijit._KeyNavContainer");
dojo.require("dijit._Templated");
dojo.declare("dijit._MenuBase",[dijit._Widget,dijit._Templated,dijit._KeyNavContainer],{parentMenu:null,popupDelay:500,startup:function(){
if(this._started){
return;
}
dojo.forEach(this.getChildren(),function(_1){
_1.startup();
});
this.startupKeyNavChildren();
this.inherited(arguments);
},onExecute:function(){
},onCancel:function(_2){
},_moveToPopup:function(_3){
if(this.focusedChild&&this.focusedChild.popup&&!this.focusedChild.disabled){
this.focusedChild._onClick(_3);
}else{
var _4=this._getTopMenu();
if(_4&&_4._isMenuBar){
_4.focusNext();
}
}
},_onPopupHover:function(_5){
if(this.currentPopup&&this.currentPopup._pendingClose_timer){
var _6=this.currentPopup.parentMenu;
if(_6.focusedChild){
_6.focusedChild._setSelected(false);
}
_6.focusedChild=this.currentPopup.from_item;
_6.focusedChild._setSelected(true);
this._stopPendingCloseTimer(this.currentPopup);
}
},onItemHover:function(_7){
if(this.isActive){
this.focusChild(_7);
if(this.focusedChild.popup&&!this.focusedChild.disabled&&!this.hover_timer){
this.hover_timer=setTimeout(dojo.hitch(this,"_openPopup"),this.popupDelay);
}
}
if(this.focusedChild){
this.focusChild(_7);
}
this._hoveredChild=_7;
},_onChildBlur:function(_8){
this._stopPopupTimer();
_8._setSelected(false);
var _9=_8.popup;
if(_9){
this._stopPendingCloseTimer(_9);
_9._pendingClose_timer=setTimeout(function(){
_9._pendingClose_timer=null;
if(_9.parentMenu){
_9.parentMenu.currentPopup=null;
}
dijit.popup.close(_9);
},this.popupDelay);
}
},onItemUnhover:function(_a){
if(this.isActive){
this._stopPopupTimer();
}
if(this._hoveredChild==_a){
this._hoveredChild=null;
}
},_stopPopupTimer:function(){
if(this.hover_timer){
clearTimeout(this.hover_timer);
this.hover_timer=null;
}
},_stopPendingCloseTimer:function(_b){
if(_b._pendingClose_timer){
clearTimeout(_b._pendingClose_timer);
_b._pendingClose_timer=null;
}
},_stopFocusTimer:function(){
if(this._focus_timer){
clearTimeout(this._focus_timer);
this._focus_timer=null;
}
},_getTopMenu:function(){
for(var _c=this;_c.parentMenu;_c=_c.parentMenu){
}
return _c;
},onItemClick:function(_d,_e){
if(_d.disabled){
return false;
}
if(typeof this.isShowingNow=="undefined"){
this._markActive();
}
this.focusChild(_d);
if(_d.popup){
this._openPopup();
}else{
this.onExecute();
_d.onClick(_e);
}
},_openPopup:function(){
this._stopPopupTimer();
var _f=this.focusedChild;
if(!_f){
return;
}
var _10=_f.popup;
if(_10.isShowingNow){
return;
}
if(this.currentPopup){
this._stopPendingCloseTimer(this.currentPopup);
dijit.popup.close(this.currentPopup);
}
_10.parentMenu=this;
_10.from_item=_f;
var _11=this;
dijit.popup.open({parent:this,popup:_10,around:_f.domNode,orient:this._orient||(this.isLeftToRight()?{"TR":"TL","TL":"TR","BR":"BL","BL":"BR"}:{"TL":"TR","TR":"TL","BL":"BR","BR":"BL"}),onCancel:function(){
_11.focusChild(_f);
_11._cleanUp();
_f._setSelected(true);
_11.focusedChild=_f;
},onExecute:dojo.hitch(this,"_cleanUp")});
this.currentPopup=_10;
_10.connect(_10.domNode,"onmouseenter",dojo.hitch(_11,"_onPopupHover"));
if(_10.focus){
_10._focus_timer=setTimeout(dojo.hitch(_10,function(){
this._focus_timer=null;
this.focus();
}),0);
}
},_markActive:function(){
this.isActive=true;
dojo.addClass(this.domNode,"dijitMenuActive");
dojo.removeClass(this.domNode,"dijitMenuPassive");
},onOpen:function(e){
this.isShowingNow=true;
this._markActive();
},_markInactive:function(){
this.isActive=false;
dojo.removeClass(this.domNode,"dijitMenuActive");
dojo.addClass(this.domNode,"dijitMenuPassive");
},onClose:function(){
this._stopFocusTimer();
this._markInactive();
this.isShowingNow=false;
this.parentMenu=null;
},_closeChild:function(){
this._stopPopupTimer();
if(this.focusedChild){
this.focusedChild._setSelected(false);
this.focusedChild._onUnhover();
this.focusedChild=null;
}
if(this.currentPopup){
dijit.popup.close(this.currentPopup);
this.currentPopup=null;
}
},_onItemFocus:function(_12){
if(this._hoveredChild&&this._hoveredChild!=_12){
this._hoveredChild._onUnhover();
}
},_onBlur:function(){
this._cleanUp();
this.inherited(arguments);
},_cleanUp:function(){
this._closeChild();
if(typeof this.isShowingNow=="undefined"){
this._markInactive();
}
}});
dojo.declare("dijit.Menu",dijit._MenuBase,{constructor:function(){
this._bindings=[];
},templateString:dojo.cache("dijit","templates/Menu.html","<table class=\"dijit dijitMenu dijitMenuPassive dijitReset dijitMenuTable\" waiRole=\"menu\" tabIndex=\"${tabIndex}\" dojoAttachEvent=\"onkeypress:_onKeyPress\">\n\t<tbody class=\"dijitReset\" dojoAttachPoint=\"containerNode\"></tbody>\n</table>\n"),targetNodeIds:[],contextMenuForWindow:false,leftClickToOpen:false,refocus:true,_contextMenuWithMouse:false,postCreate:function(){
if(this.contextMenuForWindow){
this.bindDomNode(dojo.body());
}else{
dojo.forEach(this.targetNodeIds,this.bindDomNode,this);
}
var k=dojo.keys,l=this.isLeftToRight();
this._openSubMenuKey=l?k.RIGHT_ARROW:k.LEFT_ARROW;
this._closeSubMenuKey=l?k.LEFT_ARROW:k.RIGHT_ARROW;
this.connectKeyNavHandlers([k.UP_ARROW],[k.DOWN_ARROW]);
},_onKeyPress:function(evt){
if(evt.ctrlKey||evt.altKey){
return;
}
switch(evt.charOrCode){
case this._openSubMenuKey:
this._moveToPopup(evt);
dojo.stopEvent(evt);
break;
case this._closeSubMenuKey:
if(this.parentMenu){
if(this.parentMenu._isMenuBar){
this.parentMenu.focusPrev();
}else{
this.onCancel(false);
}
}else{
dojo.stopEvent(evt);
}
break;
}
},_iframeContentWindow:function(_13){
var win=dijit.getDocumentWindow(this._iframeContentDocument(_13))||this._iframeContentDocument(_13)["__parent__"]||(_13.name&&dojo.doc.frames[_13.name])||null;
return win;
},_iframeContentDocument:function(_14){
var doc=_14.contentDocument||(_14.contentWindow&&_14.contentWindow.document)||(_14.name&&dojo.doc.frames[_14.name]&&dojo.doc.frames[_14.name].document)||null;
return doc;
},bindDomNode:function(_15){
_15=dojo.byId(_15);
var cn;
if(_15.tagName.toLowerCase()=="iframe"){
var _16=_15,win=this._iframeContentWindow(_16);
cn=dojo.withGlobal(win,dojo.body);
}else{
cn=(_15==dojo.body()?dojo.doc.documentElement:_15);
}
var _17={node:_15,iframe:_16};
dojo.attr(_15,"_dijitMenu"+this.id,this._bindings.push(_17));
var _18=dojo.hitch(this,function(cn){
return [dojo.connect(cn,(this.leftClickToOpen)?"onclick":"oncontextmenu",this,function(evt){
this._openMyself(evt,cn,_16);
}),dojo.connect(cn,"onkeydown",this,"_contextKey"),dojo.connect(cn,"onmousedown",this,"_contextMouse")];
});
_17.connects=cn?_18(cn):[];
if(_16){
_17.onloadHandler=dojo.hitch(this,function(){
var win=this._iframeContentWindow(_16);
cn=dojo.withGlobal(win,dojo.body);
_17.connects=_18(cn);
});
if(_16.addEventListener){
_16.addEventListener("load",_17.onloadHandler,false);
}else{
_16.attachEvent("onload",_17.onloadHandler);
}
}
},unBindDomNode:function(_19){
var _1a;
try{
_1a=dojo.byId(_19);
}
catch(e){
return;
}
var _1b="_dijitMenu"+this.id;
if(_1a&&dojo.hasAttr(_1a,_1b)){
var bid=dojo.attr(_1a,_1b)-1,b=this._bindings[bid];
dojo.forEach(b.connects,dojo.disconnect);
var _1c=b.iframe;
if(_1c){
if(_1c.removeEventListener){
_1c.removeEventListener("load",b.onloadHandler,false);
}else{
_1c.detachEvent("onload",b.onloadHandler);
}
}
dojo.removeAttr(_1a,_1b);
delete this._bindings[bid];
}
},_contextKey:function(e){
this._contextMenuWithMouse=false;
if(e.keyCode==dojo.keys.F10){
dojo.stopEvent(e);
if(e.shiftKey&&e.type=="keydown"){
var _1d={target:e.target,pageX:e.pageX,pageY:e.pageY};
_1d.preventDefault=_1d.stopPropagation=function(){
};
window.setTimeout(dojo.hitch(this,function(){
this._openMyself(_1d);
}),1);
}
}
},_contextMouse:function(e){
this._contextMenuWithMouse=true;
},_openMyself:function(e,_1e,_1f){
if(this.leftClickToOpen&&e.button>0){
return;
}
dojo.stopEvent(e);
var x,y;
if(dojo.isSafari||this._contextMenuWithMouse){
x=e.pageX;
y=e.pageY;
if(_1f){
var od=e.target.ownerDocument,ifc=dojo.position(_1f,true),win=this._iframeContentWindow(_1f),_20=dojo.withGlobal(win,"_docScroll",dojo);
var cs=dojo.getComputedStyle(_1f),tp=dojo._toPixelValue,_21=(dojo.isIE&&dojo.isQuirks?0:tp(_1f,cs.paddingLeft))+(dojo.isIE&&dojo.isQuirks?tp(_1f,cs.borderLeftWidth):0),top=(dojo.isIE&&dojo.isQuirks?0:tp(_1f,cs.paddingTop))+(dojo.isIE&&dojo.isQuirks?tp(_1f,cs.borderTopWidth):0);
x+=ifc.x+_21-_20.x;
y+=ifc.y+top-_20.y;
}
}else{
var _22=dojo.position(e.target,true);
x=_22.x+10;
y=_22.y+10;
}
var _23=this;
var _24=dijit.getFocus(this);
function _25(){
if(_23.refocus){
dijit.focus(_24);
}
dijit.popup.close(_23);
};
dijit.popup.open({popup:this,x:x,y:y,onExecute:_25,onCancel:_25,orient:this.isLeftToRight()?"L":"R"});
this.focus();
this._onBlur=function(){
this.inherited("_onBlur",arguments);
dijit.popup.close(this);
};
},uninitialize:function(){
dojo.forEach(this._bindings,function(b){
if(b){
this.unBindDomNode(b.node);
}
},this);
this.inherited(arguments);
}});
dojo.require("dijit.MenuItem");
dojo.require("dijit.PopupMenuItem");
dojo.require("dijit.CheckedMenuItem");
dojo.require("dijit.MenuSeparator");
}
