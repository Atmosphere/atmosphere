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
if(_c[0]._multiValue){
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
var obj={};
dojo.forEach(this.getDescendants(),function(_13){
var _14=_13.name;
if(!_14||_13.disabled){
return;
}
var _15=_13.attr("value");
if(typeof _13.checked=="boolean"){
if(/Radio/.test(_13.declaredClass)){
if(_15!==false){
dojo.setObject(_14,_15,obj);
}else{
_15=dojo.getObject(_14,false,obj);
if(_15===undefined){
dojo.setObject(_14,null,obj);
}
}
}else{
var ary=dojo.getObject(_14,false,obj);
if(!ary){
ary=[];
dojo.setObject(_14,ary,obj);
}
if(_15!==false){
ary.push(_15);
}
}
}else{
dojo.setObject(_14,_15,obj);
}
});
return obj;
},isValid:function(){
this._invalidWidgets=dojo.filter(this.getDescendants(),function(_17){
return !_17.disabled&&_17.isValid&&!_17.isValid();
});
return !this._invalidWidgets.length;
},onValidStateChange:function(_18){
},_widgetChange:function(_19){
var _1a=this._lastValidState;
if(!_19||this._lastValidState===undefined){
_1a=this.isValid();
if(this._lastValidState===undefined){
this._lastValidState=_1a;
}
}else{
if(_19.isValid){
this._invalidWidgets=dojo.filter(this._invalidWidgets||[],function(w){
return (w!=_19);
},this);
if(!_19.isValid()&&!_19.attr("disabled")){
this._invalidWidgets.push(_19);
}
_1a=(this._invalidWidgets.length===0);
}
}
if(_1a!==this._lastValidState){
this._lastValidState=_1a;
this.onValidStateChange(_1a);
}
},connectChildren:function(){
dojo.forEach(this._changeConnections,dojo.hitch(this,"disconnect"));
var _1c=this;
var _1d=this._changeConnections=[];
dojo.forEach(dojo.filter(this.getDescendants(),function(_1e){
return _1e.validate;
}),function(_1f){
_1d.push(_1c.connect(_1f,"validate",dojo.hitch(_1c,"_widgetChange",_1f)));
_1d.push(_1c.connect(_1f,"_setDisabledAttr",dojo.hitch(_1c,"_widgetChange",_1f)));
});
this._widgetChange(null);
},startup:function(){
this.inherited(arguments);
this._changeConnections=[];
this.connectChildren();
}});
}
