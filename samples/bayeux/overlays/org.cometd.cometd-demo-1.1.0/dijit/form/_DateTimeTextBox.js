/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dijit.form._DateTimeTextBox"]){
dojo._hasResource["dijit.form._DateTimeTextBox"]=true;
dojo.provide("dijit.form._DateTimeTextBox");
dojo.require("dojo.date");
dojo.require("dojo.date.locale");
dojo.require("dojo.date.stamp");
dojo.require("dijit.form.ValidationTextBox");
dojo.declare("dijit.form._DateTimeTextBox",dijit.form.RangeBoundTextBox,{regExpGen:dojo.date.locale.regexp,datePackage:"dojo.date",compare:dojo.date.compare,format:function(_1,_2){
if(!_1){
return "";
}
return this.dateLocaleModule.format(_1,_2);
},parse:function(_3,_4){
return this.dateLocaleModule.parse(_3,_4)||(this._isEmpty(_3)?null:undefined);
},serialize:function(_5,_6){
if(_5.toGregorian){
_5=_5.toGregorian();
}
return dojo.date.stamp.toISOString(_5,_6);
},value:new Date(""),_blankValue:null,popupClass:"",_selector:"",constructor:function(_7){
var _8=_7.datePackage?_7.datePackage+".Date":"Date";
this.dateClassObj=dojo.getObject(_8,false);
this.value=new this.dateClassObj("");
this.datePackage=_7.datePackage||this.datePackage;
this.dateLocaleModule=dojo.getObject(this.datePackage+".locale",false);
this.regExpGen=this.dateLocaleModule.regexp;
},postMixInProperties:function(){
this.inherited(arguments);
if(!this.value||this.value.toString()==dijit.form._DateTimeTextBox.prototype.value.toString()){
this.value=null;
}
var _9=this.constraints;
_9.selector=this._selector;
_9.fullYear=true;
var _a=dojo.date.stamp.fromISOString;
if(typeof _9.min=="string"){
_9.min=_a(_9.min);
}
if(typeof _9.max=="string"){
_9.max=_a(_9.max);
}
},_onFocus:function(_b){
this._open();
this.inherited(arguments);
},_setValueAttr:function(_c,_d,_e){
if(_c instanceof Date&&!(this.dateClassObj instanceof Date)){
_c=new this.dateClassObj(_c);
}
this.inherited(arguments);
if(this._picker){
if(!_c){
_c=new this.dateClassObj();
}
this._picker.attr("value",_c);
}
},_open:function(){
if(this.disabled||this.readOnly||!this.popupClass){
return;
}
var _f=this;
if(!this._picker){
var _10=dojo.getObject(this.popupClass,false);
this._picker=new _10({onValueSelected:function(_11){
if(_f._tabbingAway){
delete _f._tabbingAway;
}else{
_f.focus();
}
setTimeout(dojo.hitch(_f,"_close"),1);
dijit.form._DateTimeTextBox.superclass._setValueAttr.call(_f,_11,true);
},id:this.id+"_popup",lang:_f.lang,constraints:_f.constraints,datePackage:_f.datePackage,isDisabledDate:function(_12){
var _13=dojo.date.compare;
var _14=_f.constraints;
return _14&&(_14.min&&(_13(_14.min,_12,_f._selector)>0)||(_14.max&&_13(_14.max,_12,_f._selector)<0));
}});
this._picker.attr("value",this.attr("value")||new this.dateClassObj());
}
if(!this._opened){
dijit.popup.open({parent:this,popup:this._picker,orient:{"BL":"TL","TL":"BL"},around:this.domNode,onCancel:dojo.hitch(this,this._close),onClose:function(){
_f._opened=false;
}});
this._opened=true;
}
dojo.marginBox(this._picker.domNode,{w:this.domNode.offsetWidth});
},_close:function(){
if(this._opened){
dijit.popup.close(this._picker);
this._opened=false;
}
},_onBlur:function(){
this._close();
if(this._picker){
this._picker.destroy();
delete this._picker;
}
this.inherited(arguments);
},_getDisplayedValueAttr:function(){
return this.textbox.value;
},_setDisplayedValueAttr:function(_15,_16){
this._setValueAttr(this.parse(_15,this.constraints),_16,_15);
},destroy:function(){
if(this._picker){
this._picker.destroy();
delete this._picker;
}
this.inherited(arguments);
},postCreate:function(){
this.inherited(arguments);
this.connect(this.focusNode,"onkeypress",this._onKeyPress);
this.connect(this.focusNode,"onclick",this._open);
},_onKeyPress:function(e){
var p=this._picker,dk=dojo.keys;
if(p&&this._opened&&p.handleKey){
if(p.handleKey(e)===false){
return;
}
}
if(this._opened&&e.charOrCode==dk.ESCAPE&&!(e.shiftKey||e.ctrlKey||e.altKey||e.metaKey)){
this._close();
dojo.stopEvent(e);
}else{
if(!this._opened&&e.charOrCode==dk.DOWN_ARROW){
this._open();
dojo.stopEvent(e);
}else{
if(e.charOrCode===dk.TAB){
this._tabbingAway=true;
}else{
if(this._opened&&(e.keyChar||e.charOrCode===dk.BACKSPACE||e.charOrCode==dk.DELETE)){
setTimeout(dojo.hitch(this,function(){
dijit.placeOnScreenAroundElement(p.domNode.parentNode,this.domNode,{"BL":"TL","TL":"BL"},p.orient?dojo.hitch(p,"orient"):null);
}),1);
}
}
}
}
}});
}
