/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dijit.form.TextBox"]){
dojo._hasResource["dijit.form.TextBox"]=true;
dojo.provide("dijit.form.TextBox");
dojo.require("dijit.form._FormWidget");
dojo.declare("dijit.form.TextBox",dijit.form._FormValueWidget,{trim:false,uppercase:false,lowercase:false,propercase:false,maxLength:"",selectOnClick:false,templateString:dojo.cache("dijit.form","templates/TextBox.html","<input class=\"dijit dijitReset dijitLeft\" dojoAttachPoint='textbox,focusNode'\n\tdojoAttachEvent='onmouseenter:_onMouse,onmouseleave:_onMouse'\n\tautocomplete=\"off\" type=\"${type}\" ${nameAttrSetting}\n\t/>\n"),baseClass:"dijitTextBox",attributeMap:dojo.delegate(dijit.form._FormValueWidget.prototype.attributeMap,{maxLength:"focusNode"}),_getValueAttr:function(){
return this.parse(this.attr("displayedValue"),this.constraints);
},_setValueAttr:function(_1,_2,_3){
var _4;
if(_1!==undefined){
_4=this.filter(_1);
if(typeof _3!="string"){
if(_4!==null&&((typeof _4!="number")||!isNaN(_4))){
_3=this.filter(this.format(_4,this.constraints));
}else{
_3="";
}
}
}
if(_3!=null&&_3!=undefined&&((typeof _3)!="number"||!isNaN(_3))&&this.textbox.value!=_3){
this.textbox.value=_3;
}
this.inherited(arguments,[_4,_2]);
},displayedValue:"",getDisplayedValue:function(){
dojo.deprecated(this.declaredClass+"::getDisplayedValue() is deprecated. Use attr('displayedValue') instead.","","2.0");
return this.attr("displayedValue");
},_getDisplayedValueAttr:function(){
return this.filter(this.textbox.value);
},setDisplayedValue:function(_5){
dojo.deprecated(this.declaredClass+"::setDisplayedValue() is deprecated. Use attr('displayedValue', ...) instead.","","2.0");
this.attr("displayedValue",_5);
},_setDisplayedValueAttr:function(_6){
if(_6===null||_6===undefined){
_6="";
}else{
if(typeof _6!="string"){
_6=String(_6);
}
}
this.textbox.value=_6;
this._setValueAttr(this.attr("value"),undefined,_6);
},format:function(_7,_8){
return ((_7==null||_7==undefined)?"":(_7.toString?_7.toString():_7));
},parse:function(_9,_a){
return _9;
},_refreshState:function(){
},_onInput:function(e){
if(e&&e.type&&/key/i.test(e.type)&&e.keyCode){
switch(e.keyCode){
case dojo.keys.SHIFT:
case dojo.keys.ALT:
case dojo.keys.CTRL:
case dojo.keys.TAB:
return;
}
}
if(this.intermediateChanges){
var _b=this;
setTimeout(function(){
_b._handleOnChange(_b.attr("value"),false);
},0);
}
this._refreshState();
},postCreate:function(){
this.textbox.setAttribute("value",this.textbox.value);
this.inherited(arguments);
if(dojo.isMoz||dojo.isOpera){
this.connect(this.textbox,"oninput",this._onInput);
}else{
this.connect(this.textbox,"onkeydown",this._onInput);
this.connect(this.textbox,"onkeyup",this._onInput);
this.connect(this.textbox,"onpaste",this._onInput);
this.connect(this.textbox,"oncut",this._onInput);
}
},_blankValue:"",filter:function(_c){
if(_c===null){
return this._blankValue;
}
if(typeof _c!="string"){
return _c;
}
if(this.trim){
_c=dojo.trim(_c);
}
if(this.uppercase){
_c=_c.toUpperCase();
}
if(this.lowercase){
_c=_c.toLowerCase();
}
if(this.propercase){
_c=_c.replace(/[^\s]+/g,function(_d){
return _d.substring(0,1).toUpperCase()+_d.substring(1);
});
}
return _c;
},_setBlurValue:function(){
this._setValueAttr(this.attr("value"),true);
},_onBlur:function(e){
if(this.disabled){
return;
}
this._setBlurValue();
this.inherited(arguments);
if(this._selectOnClickHandle){
this.disconnect(this._selectOnClickHandle);
}
if(this.selectOnClick&&dojo.isMoz){
this.textbox.selectionStart=this.textbox.selectionEnd=undefined;
}
},_onFocus:function(by){
if(this.disabled||this.readOnly){
return;
}
if(this.selectOnClick&&by=="mouse"){
this._selectOnClickHandle=this.connect(this.domNode,"onmouseup",function(){
this.disconnect(this._selectOnClickHandle);
var _e;
if(dojo.isIE){
var _f=dojo.doc.selection.createRange();
var _10=_f.parentElement();
_e=_10==this.textbox&&_f.text.length==0;
}else{
_e=this.textbox.selectionStart==this.textbox.selectionEnd;
}
if(_e){
dijit.selectInputText(this.textbox);
}
});
}
this._refreshState();
this.inherited(arguments);
},reset:function(){
this.textbox.value="";
this.inherited(arguments);
}});
dijit.selectInputText=function(_11,_12,_13){
var _14=dojo.global;
var _15=dojo.doc;
_11=dojo.byId(_11);
if(isNaN(_12)){
_12=0;
}
if(isNaN(_13)){
_13=_11.value?_11.value.length:0;
}
dijit.focus(_11);
if(_15["selection"]&&dojo.body()["createTextRange"]){
if(_11.createTextRange){
var _16=_11.createTextRange();
with(_16){
collapse(true);
moveStart("character",-99999);
moveStart("character",_12);
moveEnd("character",_13-_12);
select();
}
}
}else{
if(_14["getSelection"]){
if(_11.setSelectionRange){
_11.setSelectionRange(_12,_13);
}
}
}
};
}
