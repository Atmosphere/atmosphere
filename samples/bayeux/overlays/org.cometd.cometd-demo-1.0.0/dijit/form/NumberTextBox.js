/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dijit.form.NumberTextBox"]){
dojo._hasResource["dijit.form.NumberTextBox"]=true;
dojo.provide("dijit.form.NumberTextBox");
dojo.require("dijit.form.ValidationTextBox");
dojo.require("dojo.number");
dojo.declare("dijit.form.NumberTextBoxMixin",null,{regExpGen:dojo.number.regexp,value:NaN,editOptions:{pattern:"#.######"},_formatter:dojo.number.format,postMixInProperties:function(){
if(typeof this.constraints.max!="number"){
this.constraints.max=9000000000000000;
}
this.inherited(arguments);
},_onFocus:function(){
if(this.disabled){
return;
}
var _1=this.attr("value");
if(typeof _1=="number"&&!isNaN(_1)){
var _2=this.format(_1,this.constraints);
if(_2!==undefined){
this.textbox.value=_2;
}
}
this.inherited(arguments);
},format:function(_3,_4){
if(typeof _3!="number"){
return String(_3);
}
if(isNaN(_3)){
return "";
}
if(("rangeCheck" in this)&&!this.rangeCheck(_3,_4)){
return String(_3);
}
if(this.editOptions&&this._focused){
_4=dojo.mixin(dojo.mixin({},this.editOptions),_4);
}
return this._formatter(_3,_4);
},parse:dojo.number.parse,_getDisplayedValueAttr:function(){
var v=this.inherited(arguments);
return isNaN(v)?this.textbox.value:v;
},filter:function(_6){
return (_6===null||_6===""||_6===undefined)?NaN:this.inherited(arguments);
},serialize:function(_7,_8){
return (typeof _7!="number"||isNaN(_7))?"":this.inherited(arguments);
},_setValueAttr:function(_9,_a,_b){
if(_9!==undefined&&_b===undefined){
if(typeof _9=="number"){
if(isNaN(_9)){
_b="";
}else{
if(("rangeCheck" in this)&&!this.rangeCheck(_9,this.constraints)){
_b=String(_9);
}
}
}else{
if(!_9){
_b="";
_9=NaN;
}else{
_b=String(_9);
_9=undefined;
}
}
}
this.inherited(arguments,[_9,_a,_b]);
},_getValueAttr:function(){
var v=this.inherited(arguments);
if(isNaN(v)&&this.textbox.value!==""){
var n=Number(this.textbox.value);
return (String(n)===this.textbox.value)?n:undefined;
}else{
return v;
}
}});
dojo.declare("dijit.form.NumberTextBox",[dijit.form.RangeBoundTextBox,dijit.form.NumberTextBoxMixin],{});
}
