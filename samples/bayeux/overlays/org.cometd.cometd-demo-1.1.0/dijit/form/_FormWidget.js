/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dijit.form._FormWidget"]){
dojo._hasResource["dijit.form._FormWidget"]=true;
dojo.provide("dijit.form._FormWidget");
dojo.require("dijit._Widget");
dojo.require("dijit._Templated");
dojo.declare("dijit.form._FormWidget",[dijit._Widget,dijit._Templated],{baseClass:"",name:"",alt:"",value:"",type:"text",tabIndex:"0",disabled:false,intermediateChanges:false,scrollOnFocus:true,attributeMap:dojo.delegate(dijit._Widget.prototype.attributeMap,{value:"focusNode",id:"focusNode",tabIndex:"focusNode",alt:"focusNode",title:"focusNode"}),postMixInProperties:function(){
this.nameAttrSetting=this.name?("name='"+this.name+"'"):"";
this.inherited(arguments);
},_setDisabledAttr:function(_1){
this.disabled=_1;
dojo.attr(this.focusNode,"disabled",_1);
if(this.valueNode){
dojo.attr(this.valueNode,"disabled",_1);
}
dijit.setWaiState(this.focusNode,"disabled",_1);
if(_1){
this._hovering=false;
this._active=false;
this.focusNode.setAttribute("tabIndex","-1");
}else{
this.focusNode.setAttribute("tabIndex",this.tabIndex);
}
this._setStateClass();
},setDisabled:function(_2){
dojo.deprecated("setDisabled("+_2+") is deprecated. Use attr('disabled',"+_2+") instead.","","2.0");
this.attr("disabled",_2);
},_onFocus:function(e){
if(this.scrollOnFocus){
dijit.scrollIntoView(this.domNode);
}
this.inherited(arguments);
},_onMouse:function(_3){
var _4=_3.currentTarget;
if(_4&&_4.getAttribute){
this.stateModifier=_4.getAttribute("stateModifier")||"";
}
if(!this.disabled){
switch(_3.type){
case "mouseenter":
case "mouseover":
this._hovering=true;
this._active=this._mouseDown;
break;
case "mouseout":
case "mouseleave":
this._hovering=false;
this._active=false;
break;
case "mousedown":
this._active=true;
this._mouseDown=true;
var _5=this.connect(dojo.body(),"onmouseup",function(){
if(this._mouseDown&&this.isFocusable()){
this.focus();
}
this._active=false;
this._mouseDown=false;
this._setStateClass();
this.disconnect(_5);
});
break;
}
this._setStateClass();
}
},isFocusable:function(){
return !this.disabled&&!this.readOnly&&this.focusNode&&(dojo.style(this.domNode,"display")!="none");
},focus:function(){
dijit.focus(this.focusNode);
},_setStateClass:function(){
var _6=this.baseClass.split(" ");
function _7(_8){
_6=_6.concat(dojo.map(_6,function(c){
return c+_8;
}),"dijit"+_8);
};
if(this.checked){
_7("Checked");
}
if(this.state){
_7(this.state);
}
if(this.selected){
_7("Selected");
}
if(this.disabled){
_7("Disabled");
}else{
if(this.readOnly){
_7("ReadOnly");
}else{
if(this._active){
_7(this.stateModifier+"Active");
}else{
if(this._focused){
_7("Focused");
}
if(this._hovering){
_7(this.stateModifier+"Hover");
}
}
}
}
var tn=this.stateNode||this.domNode,_9={};
dojo.forEach(tn.className.split(" "),function(c){
_9[c]=true;
});
if("_stateClasses" in this){
dojo.forEach(this._stateClasses,function(c){
delete _9[c];
});
}
dojo.forEach(_6,function(c){
_9[c]=true;
});
var _a=[];
for(var c in _9){
_a.push(c);
}
tn.className=_a.join(" ");
this._stateClasses=_6;
},compare:function(_b,_c){
if(typeof _b=="number"&&typeof _c=="number"){
return (isNaN(_b)&&isNaN(_c))?0:_b-_c;
}else{
if(_b>_c){
return 1;
}else{
if(_b<_c){
return -1;
}else{
return 0;
}
}
}
},onChange:function(_d){
},_onChangeActive:false,_handleOnChange:function(_e,_f){
this._lastValue=_e;
if(this._lastValueReported==undefined&&(_f===null||!this._onChangeActive)){
this._resetValue=this._lastValueReported=_e;
}
if((this.intermediateChanges||_f||_f===undefined)&&((typeof _e!=typeof this._lastValueReported)||this.compare(_e,this._lastValueReported)!=0)){
this._lastValueReported=_e;
if(this._onChangeActive){
if(this._onChangeHandle){
clearTimeout(this._onChangeHandle);
}
this._onChangeHandle=setTimeout(dojo.hitch(this,function(){
this._onChangeHandle=null;
this.onChange(_e);
}),0);
}
}
},create:function(){
this.inherited(arguments);
this._onChangeActive=true;
this._setStateClass();
},destroy:function(){
if(this._onChangeHandle){
clearTimeout(this._onChangeHandle);
this.onChange(this._lastValueReported);
}
this.inherited(arguments);
},setValue:function(_10){
dojo.deprecated("dijit.form._FormWidget:setValue("+_10+") is deprecated.  Use attr('value',"+_10+") instead.","","2.0");
this.attr("value",_10);
},getValue:function(){
dojo.deprecated(this.declaredClass+"::getValue() is deprecated. Use attr('value') instead.","","2.0");
return this.attr("value");
}});
dojo.declare("dijit.form._FormValueWidget",dijit.form._FormWidget,{readOnly:false,attributeMap:dojo.delegate(dijit.form._FormWidget.prototype.attributeMap,{value:"",readOnly:"focusNode"}),_setReadOnlyAttr:function(_11){
this.readOnly=_11;
dojo.attr(this.focusNode,"readOnly",_11);
dijit.setWaiState(this.focusNode,"readonly",_11);
this._setStateClass();
},postCreate:function(){
if(dojo.isIE){
this.connect(this.focusNode||this.domNode,"onkeydown",this._onKeyDown);
}
if(this._resetValue===undefined){
this._resetValue=this.value;
}
},_setValueAttr:function(_12,_13){
this.value=_12;
this._handleOnChange(_12,_13);
},_getValueAttr:function(){
return this._lastValue;
},undo:function(){
this._setValueAttr(this._lastValueReported,false);
},reset:function(){
this._hasBeenBlurred=false;
this._setValueAttr(this._resetValue,true);
},_onKeyDown:function(e){
if(e.keyCode==dojo.keys.ESCAPE&&!(e.ctrlKey||e.altKey||e.metaKey)){
var te;
if(dojo.isIE){
e.preventDefault();
te=document.createEventObject();
te.keyCode=dojo.keys.ESCAPE;
te.shiftKey=e.shiftKey;
e.srcElement.fireEvent("onkeypress",te);
}
}
},_layoutHackIE7:function(){
if(dojo.isIE==7){
var _14=this.domNode;
var _15=_14.parentNode;
var _16=_14.firstChild||_14;
var _17=_16.style.filter;
while(_15&&_15.clientHeight==0){
_15._disconnectHandle=this.connect(_15,"onscroll",dojo.hitch(this,function(e){
this.disconnect(_15._disconnectHandle);
_15.removeAttribute("_disconnectHandle");
_16.style.filter=(new Date()).getMilliseconds();
setTimeout(function(){
_16.style.filter=_17;
},0);
}));
_15=_15.parentNode;
}
}
}});
}
