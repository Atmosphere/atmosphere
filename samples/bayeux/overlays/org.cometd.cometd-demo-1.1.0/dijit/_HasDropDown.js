/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dijit._HasDropDown"]){
dojo._hasResource["dijit._HasDropDown"]=true;
dojo.provide("dijit._HasDropDown");
dojo.require("dijit._base.place");
dojo.require("dijit._Widget");
dojo.declare("dijit._HasDropDown",null,{_buttonNode:null,_arrowWrapperNode:null,_popupStateNode:null,_aroundNode:null,dropDown:null,autoWidth:true,forceWidth:false,maxHeight:0,dropDownPosition:["below","above"],_stopClickEvents:true,_onDropDownMouse:function(e){
if(e.type=="click"&&!this._seenKeydown){
return;
}
this._seenKeydown=false;
if(e.type=="mousedown"){
this._docHandler=this.connect(dojo.doc,"onmouseup","_onDropDownMouseup");
}
if(this.disabled||this.readOnly){
return;
}
if(this._stopClickEvents){
dojo.stopEvent(e);
}
this.toggleDropDown();
if(e.type=="click"||e.type=="keypress"){
this._onDropDownMouseup();
}
},_onDropDownMouseup:function(e){
if(e&&this._docHandler){
this.disconnect(this._docHandler);
}
var _1=this.dropDown,_2=false;
if(e&&this._opened){
var c=dojo.position(this._buttonNode,true);
if(!(e.pageX>=c.x&&e.pageX<=c.x+c.w)||!(e.pageY>=c.y&&e.pageY<=c.y+c.h)){
var t=e.target;
while(t&&!_2){
if(dojo.hasClass(t,"dijitPopup")){
_2=true;
}else{
t=t.parentNode;
}
}
if(_2){
t=e.target;
if(_1.onItemClick){
var _3;
while(t&&!(_3=dijit.byNode(t))){
t=t.parentNode;
}
if(_3&&_3.onClick&&_3.getParent){
_3.getParent().onItemClick(_3,e);
}
}
return;
}
}
}
if(this._opened&&_1.focus){
window.setTimeout(dojo.hitch(_1,"focus"),1);
}
},_setupDropdown:function(){
this._buttonNode=this._buttonNode||this.focusNode||this.domNode;
this._popupStateNode=this._popupStateNode||this.focusNode||this._buttonNode;
this._aroundNode=this._aroundNode||this.domNode;
this.connect(this._buttonNode,"onmousedown","_onDropDownMouse");
this.connect(this._buttonNode,"onclick","_onDropDownMouse");
this.connect(this._buttonNode,"onkeydown","_onDropDownKeydown");
this.connect(this._buttonNode,"onblur","_onDropDownBlur");
this.connect(this._buttonNode,"onkeypress","_onKey");
if(this._setStateClass){
this.connect(this,"openDropDown","_setStateClass");
this.connect(this,"closeDropDown","_setStateClass");
}
var _4={"after":this.isLeftToRight()?"Right":"Left","before":this.isLeftToRight()?"Left":"Right","above":"Up","below":"Down","left":"Left","right":"Right"}[this.dropDownPosition[0]]||this.dropDownPosition[0]||"Down";
dojo.addClass(this._arrowWrapperNode||this._buttonNode,"dijit"+_4+"ArrowButton");
},postCreate:function(){
this._setupDropdown();
this.inherited(arguments);
},destroyDescendants:function(){
if(this.dropDown){
if(!this.dropDown._destroyed){
this.dropDown.destroyRecursive();
}
delete this.dropDown;
}
this.inherited(arguments);
},_onDropDownKeydown:function(e){
this._seenKeydown=true;
},_onKeyPress:function(e){
if(this._opened&&e.charOrCode==dojo.keys.ESCAPE&&!e.shiftKey&&!e.ctrlKey&&!e.altKey){
this.toggleDropDown();
dojo.stopEvent(e);
return;
}
this.inherited(arguments);
},_onDropDownBlur:function(e){
this._seenKeydown=false;
},_onKey:function(e){
if(this.disabled||this.readOnly){
return;
}
var d=this.dropDown;
if(d&&this._opened&&d.handleKey){
if(d.handleKey(e)===false){
return;
}
}
if(d&&this._opened&&e.keyCode==dojo.keys.ESCAPE){
this.toggleDropDown();
return;
}
if(e.keyCode==dojo.keys.DOWN_ARROW||e.keyCode==dojo.keys.ENTER||e.charOrCode==" "){
this._onDropDownMouse(e);
}
},_onBlur:function(){
this.closeDropDown();
this.inherited(arguments);
},isLoaded:function(){
return true;
},loadDropDown:function(_5){
_5();
},toggleDropDown:function(){
if(this.disabled||this.readOnly){
return;
}
this.focus();
var _6=this.dropDown;
if(!_6){
return;
}
if(!this._opened){
if(!this.isLoaded()){
this.loadDropDown(dojo.hitch(this,"openDropDown"));
return;
}else{
this.openDropDown();
}
}else{
this.closeDropDown();
}
},openDropDown:function(){
var _7=this.dropDown;
var _8=_7.domNode;
var _9=this;
if(!this._preparedNode){
dijit.popup.moveOffScreen(_8);
this._preparedNode=true;
if(_8.style.width){
this._explicitDDWidth=true;
}
if(_8.style.height){
this._explicitDDHeight=true;
}
}
if(this.maxHeight||this.forceWidth||this.autoWidth){
var _a={display:"",visibility:"hidden"};
if(!this._explicitDDWidth){
_a.width="";
}
if(!this._explicitDDHeight){
_a.height="";
}
dojo.style(_8,_a);
var mb=dojo.marginBox(_8);
var _b=(this.maxHeight&&mb.h>this.maxHeight);
dojo.style(_8,{overflow:_b?"auto":"hidden"});
if(this.forceWidth){
mb.w=this.domNode.offsetWidth;
}else{
if(this.autoWidth){
mb.w=Math.max(mb.w,this.domNode.offsetWidth);
}else{
delete mb.w;
}
}
if(_b){
mb.h=this.maxHeight;
if("w" in mb){
mb.w+=16;
}
}else{
delete mb.h;
}
delete mb.t;
delete mb.l;
if(dojo.isFunction(_7.resize)){
_7.resize(mb);
}else{
dojo.marginBox(_8,mb);
}
}
var _c=dijit.popup.open({parent:this,popup:_7,around:this._aroundNode,orient:dijit.getPopupAroundAlignment((this.dropDownPosition&&this.dropDownPosition.length)?this.dropDownPosition:["below"],this.isLeftToRight()),onExecute:function(){
_9.closeDropDown(true);
},onCancel:function(){
_9.closeDropDown(true);
},onClose:function(){
dojo.attr(_9._popupStateNode,"popupActive",false);
dojo.removeClass(_9._popupStateNode,"dijitHasDropDownOpen");
_9._opened=false;
_9.state="";
}});
dojo.attr(this._popupStateNode,"popupActive","true");
dojo.addClass(_9._popupStateNode,"dijitHasDropDownOpen");
this._opened=true;
this.state="Opened";
return _c;
},closeDropDown:function(_d){
if(this._opened){
dijit.popup.close(this.dropDown);
if(_d){
this.focus();
}
this._opened=false;
this.state="";
}
}});
}
