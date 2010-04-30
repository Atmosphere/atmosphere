/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dijit._base.manager"]){
dojo._hasResource["dijit._base.manager"]=true;
dojo.provide("dijit._base.manager");
dojo.declare("dijit.WidgetSet",null,{constructor:function(){
this._hash={};
},add:function(_1){
if(this._hash[_1.id]){
throw new Error("Tried to register widget with id=="+_1.id+" but that id is already registered");
}
this._hash[_1.id]=_1;
},remove:function(id){
delete this._hash[id];
},forEach:function(_3){
for(var id in this._hash){
_3(this._hash[id]);
}
},filter:function(_5){
var _6=new dijit.WidgetSet();
this.forEach(function(_7){
if(_5(_7)){
_6.add(_7);
}
});
return _6;
},byId:function(id){
return this._hash[id];
},byClass:function(_9){
return this.filter(function(_a){
return _a.declaredClass==_9;
});
}});
dijit.registry=new dijit.WidgetSet();
dijit._widgetTypeCtr={};
dijit.getUniqueId=function(_b){
var id;
do{
id=_b+"_"+(_b in dijit._widgetTypeCtr?++dijit._widgetTypeCtr[_b]:dijit._widgetTypeCtr[_b]=0);
}while(dijit.byId(id));
return id;
};
dijit.findWidgets=function(_d){
var _e=[];
function _f(_10){
var _11=dojo.isIE?_10.children:_10.childNodes,i=0,_13;
while(_13=_11[i++]){
if(_13.nodeType!=1){
continue;
}
var _14=_13.getAttribute("widgetId");
if(_14){
var _15=dijit.byId(_14);
_e.push(_15);
}else{
_f(_13);
}
}
};
_f(_d);
return _e;
};
if(dojo.isIE){
dojo.addOnWindowUnload(function(){
dojo.forEach(dijit.findWidgets(dojo.body()),function(_16){
if(_16.destroyRecursive){
_16.destroyRecursive();
}else{
if(_16.destroy){
_16.destroy();
}
}
});
});
}
dijit.byId=function(id){
return (dojo.isString(id))?dijit.registry.byId(id):id;
};
dijit.byNode=function(_18){
return dijit.registry.byId(_18.getAttribute("widgetId"));
};
dijit.getEnclosingWidget=function(_19){
while(_19){
if(_19.getAttribute&&_19.getAttribute("widgetId")){
return dijit.registry.byId(_19.getAttribute("widgetId"));
}
_19=_19.parentNode;
}
return null;
};
dijit._tabElements={area:true,button:true,input:true,object:true,select:true,textarea:true};
dijit._isElementShown=function(_1a){
var _1b=dojo.style(_1a);
return (_1b.visibility!="hidden")&&(_1b.visibility!="collapsed")&&(_1b.display!="none")&&(dojo.attr(_1a,"type")!="hidden");
};
dijit.isTabNavigable=function(_1c){
if(dojo.hasAttr(_1c,"disabled")){
return false;
}
var _1d=dojo.hasAttr(_1c,"tabindex");
var _1e=dojo.attr(_1c,"tabindex");
if(_1d&&_1e>=0){
return true;
}
var _1f=_1c.nodeName.toLowerCase();
if(((_1f=="a"&&dojo.hasAttr(_1c,"href"))||dijit._tabElements[_1f])&&(!_1d||_1e>=0)){
return true;
}
return false;
};
dijit._getTabNavigable=function(_20){
var _21,_22,_23,_24,_25,_26;
var _27=function(_28){
dojo.query("> *",_28).forEach(function(_29){
var _2a=dijit._isElementShown(_29);
if(_2a&&dijit.isTabNavigable(_29)){
var _2b=dojo.attr(_29,"tabindex");
if(!dojo.hasAttr(_29,"tabindex")||_2b==0){
if(!_21){
_21=_29;
}
_22=_29;
}else{
if(_2b>0){
if(!_23||_2b<_24){
_24=_2b;
_23=_29;
}
if(!_25||_2b>=_26){
_26=_2b;
_25=_29;
}
}
}
}
if(_2a&&_29.nodeName.toUpperCase()!="SELECT"){
_27(_29);
}
});
};
if(dijit._isElementShown(_20)){
_27(_20);
}
return {first:_21,last:_22,lowest:_23,highest:_25};
};
dijit.getFirstInTabbingOrder=function(_2c){
var _2d=dijit._getTabNavigable(dojo.byId(_2c));
return _2d.lowest?_2d.lowest:_2d.first;
};
dijit.getLastInTabbingOrder=function(_2e){
var _2f=dijit._getTabNavigable(dojo.byId(_2e));
return _2f.last?_2f.last:_2f.highest;
};
dijit.defaultDuration=dojo.config["defaultDuration"]||200;
}
