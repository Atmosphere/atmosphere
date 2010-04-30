/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dijit.form._FormMixin"]){
dojo._hasResource["dijit.form._FormMixin"]=true;
dojo.provide("dijit.form._FormMixin");
dojo.declare("dijit.form._FormMixin",null,{reset:function(){
dojo.forEach(this.getDescendants(),function(_1){
if(_1.reset){
_1.reset();
}
});
},validate:function(){
var _2=false;
return dojo.every(dojo.map(this.getDescendants(),function(_3){
_3._hasBeenBlurred=true;
var _4=_3.disabled||!_3.validate||_3.validate();
if(!_4&&!_2){
dijit.scrollIntoView(_3.containerNode||_3.domNode);
_3.focus();
_2=true;
}
return _4;
}),function(_5){
return _5;
});
},setValues:function(_6){
dojo.deprecated(this.declaredClass+"::setValues() is deprecated. Use attr('value', val) instead.","","2.0");
return this.attr("value",_6);
},_setValueAttr:function(_7){
var _8={};
dojo.forEach(this.getDescendants(),function(_9){
if(!_9.name){
return;
}
var _a=_8[_9.name]||(_8[_9.name]=[]);
_a.push(_9);
});
for(var _b in _8){
if(!_8.hasOwnProperty(_b)){
continue;
}
var _c=_8[_b],_d=dojo.getObject(_b,false,_7);
if(_d===undefined){
continue;
}
if(!dojo.isArray(_d)){
_d=[_d];
}
if(typeof _c[0].checked=="boolean"){
dojo.forEach(_c,function(w,i){
w.attr("value",dojo.indexOf(_d,w.value)!=-1);
});
}else{
if(_c[0].multiple){
_c[0].attr("value",_d);
}else{
dojo.forEach(_c,function(w,i){
w.attr("value",_d[i]);
});
}
}
}
},getValues:function(){
dojo.deprecated(this.declaredClass+"::getValues() is deprecated. Use attr('value') instead.","","2.0");
return this.attr("value");
},_getValueAttr:function(){
var _e={};
dojo.forEach(this.getDescendants(),function(_f){
var _10=_f.name;
if(!_10||_f.disabled){
return;
}
var _11=_f.attr("value");
if(typeof _f.checked=="boolean"){
if(/Radio/.test(_f.declaredClass)){
if(_11!==false){
dojo.setObject(_10,_11,_e);
}else{
_11=dojo.getObject(_10,false,_e);
if(_11===undefined){
dojo.setObject(_10,null,_e);
}
}
}else{
var ary=dojo.getObject(_10,false,_e);
if(!ary){
ary=[];
dojo.setObject(_10,ary,_e);
}
if(_11!==false){
ary.push(_11);
}
}
}else{
var _12=dojo.getObject(_10,false,_e);
if(typeof _12!="undefined"){
if(dojo.isArray(_12)){
_12.push(_11);
}else{
dojo.setObject(_10,[_12,_11],_e);
}
}else{
dojo.setObject(_10,_11,_e);
}
}
});
return _e;
},isValid:function(){
this._invalidWidgets=dojo.filter(this.getDescendants(),function(_13){
return !_13.disabled&&_13.isValid&&!_13.isValid();
});
return !this._invalidWidgets.length;
},onValidStateChange:function(_14){
},_widgetChange:function(_15){
var _16=this._lastValidState;
if(!_15||this._lastValidState===undefined){
_16=this.isValid();
if(this._lastValidState===undefined){
this._lastValidState=_16;
}
}else{
if(_15.isValid){
this._invalidWidgets=dojo.filter(this._invalidWidgets||[],function(w){
return (w!=_15);
},this);
if(!_15.isValid()&&!_15.attr("disabled")){
this._invalidWidgets.push(_15);
}
_16=(this._invalidWidgets.length===0);
}
}
if(_16!==this._lastValidState){
this._lastValidState=_16;
this.onValidStateChange(_16);
}
},connectChildren:function(){
dojo.forEach(this._changeConnections,dojo.hitch(this,"disconnect"));
var _17=this;
var _18=this._changeConnections=[];
dojo.forEach(dojo.filter(this.getDescendants(),function(_19){
return _19.validate;
}),function(_1a){
_18.push(_17.connect(_1a,"validate",dojo.hitch(_17,"_widgetChange",_1a)));
_18.push(_17.connect(_1a,"_setDisabledAttr",dojo.hitch(_17,"_widgetChange",_1a)));
});
this._widgetChange(null);
},startup:function(){
this.inherited(arguments);
this._changeConnections=[];
this.connectChildren();
}});
}
