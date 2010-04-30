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
var _1=typeof this.constraints.places=="number"?this.constraints.places:0;
if(_1){
_1++;
}
if(typeof this.constraints.max!="number"){
this.constraints.max=9*Math.pow(10,15-_1);
}
if(typeof this.constraints.min!="number"){
this.constraints.min=-9*Math.pow(10,15-_1);
}
this.inherited(arguments);
},_onFocus:function(){
if(this.disabled){
return;
}
var _2=this.attr("value");
if(typeof _2=="number"&&!isNaN(_2)){
var _3=this.format(_2,this.constraints);
if(_3!==undefined){
this.textbox.value=_3;
}
}
this.inherited(arguments);
},format:function(_4,_5){
if(typeof _4!="number"){
return String(_4);
}
if(isNaN(_4)){
return "";
}
if(("rangeCheck" in this)&&!this.rangeCheck(_4,_5)){
return String(_4);
}
if(this.editOptions&&this._focused){
_5=dojo.mixin({},_5,this.editOptions);
}
return this._formatter(_4,_5);
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
if(this.constraints.exponent!==false&&/\de[-+]?|\d/i.test(this.textbox.value)&&(new RegExp("^"+dojo.number._realNumberRegexp(dojo.mixin({},this.constraints))+"$").test(this.textbox.value))){
var n=Number(this.textbox.value);
return isNaN(n)?undefined:n;
}else{
return undefined;
}
}else{
return v;
}
},isValid:function(_c){
if(!this._focused||this._isEmpty(this.textbox.value)){
return this.inherited(arguments);
}else{
var v=this.attr("value");
if(!isNaN(v)&&this.rangeCheck(v,this.constraints)){
if(this.constraints.exponent!==false&&/\de[-+]?\d/i.test(this.textbox.value)){
return true;
}else{
return this.inherited(arguments);
}
}else{
return false;
}
}
}});
dojo.declare("dijit.form.NumberTextBox",[dijit.form.RangeBoundTextBox,dijit.form.NumberTextBoxMixin],{});
}
