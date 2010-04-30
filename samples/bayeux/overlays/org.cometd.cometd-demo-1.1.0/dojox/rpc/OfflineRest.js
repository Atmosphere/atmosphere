/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dojox.rpc.OfflineRest"]){
dojo._hasResource["dojox.rpc.OfflineRest"]=true;
dojo.provide("dojox.rpc.OfflineRest");
dojo.require("dojox.data.ClientFilter");
dojo.require("dojox.rpc.Rest");
dojo.require("dojox.storage");
(function(){
var _1=dojox.rpc.Rest;
var _2="dojox_rpc_OfflineRest";
var _3;
var _4=_1._index;
dojox.storage.manager.addOnLoad(function(){
_3=dojox.storage.manager.available;
for(var i in _4){
_5(_4[i],i);
}
});
var _6;
function _7(_8){
return _8.replace(/[^0-9A-Za-z_]/g,"_");
};
function _5(_9,id){
if(_3&&!_6&&(id||(_9&&_9.__id))){
dojox.storage.put(_7(id||_9.__id),typeof _9=="object"?dojox.json.ref.toJson(_9):_9,function(){
},_2);
}
};
function _a(_b){
return _b instanceof Error&&(_b.status==503||_b.status>12000||!_b.status);
};
function _c(){
if(_3){
var _d=dojox.storage.get("dirty",_2);
if(_d){
for(var _e in _d){
_f(_e,_d);
}
}
}
};
var _10;
function _11(){
_10.sendChanges();
_10.downloadChanges();
};
var _12=setInterval(_11,15000);
dojo.connect(document,"ononline",_11);
_10=dojox.rpc.OfflineRest={turnOffAutoSync:function(){
clearInterval(_12);
},sync:_11,sendChanges:_c,downloadChanges:function(){
},addStore:function(_13,_14){
_10.stores.push(_13);
_13.fetch({queryOptions:{cache:true},query:_14,onComplete:function(_15,_16){
_13._localBaseResults=_15;
_13._localBaseFetch=_16;
}});
}};
_10.stores=[];
var _17=_1._get;
_1._get=function(_18,id){
try{
_c();
if(window.navigator&&navigator.onLine===false){
throw new Error();
}
var dfd=_17(_18,id);
}
catch(e){
dfd=new dojo.Deferred();
dfd.errback(e);
}
var _19=dojox.rpc._sync;
dfd.addCallback(function(_1a){
_5(_1a,_18._getRequest(id).url);
return _1a;
});
dfd.addErrback(function(_1b){
if(_3){
if(_a(_1b)){
var _1c={};
var _1d=function(id,_1e){
if(_1c[id]){
return _1e;
}
var _1f=dojo.fromJson(dojox.storage.get(_7(id),_2))||_1e;
_1c[id]=_1f;
for(var i in _1f){
var val=_1f[i];
id=val&&val.$ref;
if(id){
if(id.substring&&id.substring(0,4)=="cid:"){
id=id.substring(4);
}
_1f[i]=_1d(id,val);
}
}
if(_1f instanceof Array){
for(i=0;i<_1f.length;i++){
if(_1f[i]===undefined){
_1f.splice(i--,1);
}
}
}
return _1f;
};
_6=true;
var _20=_1d(_18._getRequest(id).url);
if(!_20){
return _1b;
}
_6=false;
return _20;
}else{
return _1b;
}
}else{
if(_19){
return new Error("Storage manager not loaded, can not continue");
}
dfd=new dojo.Deferred();
dfd.addCallback(arguments.callee);
dojox.storage.manager.addOnLoad(function(){
dfd.callback();
});
return dfd;
}
});
return dfd;
};
function _21(_22,_23,_24,_25,_26){
if(_22=="delete"){
dojox.storage.remove(_7(_23),_2);
}else{
dojox.storage.put(_7(_24),_25,function(){
},_2);
}
var _27=_26&&_26._store;
if(_27){
_27.updateResultSet(_27._localBaseResults,_27._localBaseFetch);
dojox.storage.put(_7(_26._getRequest(_27._localBaseFetch.query).url),dojox.json.ref.toJson(_27._localBaseResults),function(){
},_2);
}
};
dojo.addOnLoad(function(){
dojo.connect(dojox.data,"restListener",function(_28){
var _29=_28.channel;
var _2a=_28.event.toLowerCase();
var _2b=dojox.rpc.JsonRest&&dojox.rpc.JsonRest.getServiceAndId(_29).service;
_21(_2a,_29,_2a=="post"?_29+_28.result.id:_29,dojo.toJson(_28.result),_2b);
});
});
var _2c=_1._change;
_1._change=function(_2d,_2e,id,_2f){
if(!_3){
return _2c.apply(this,arguments);
}
var _30=_2e._getRequest(id).url;
_21(_2d,_30,dojox.rpc.JsonRest._contentId,_2f,_2e);
var _31=dojox.storage.get("dirty",_2)||{};
if(_2d=="put"||_2d=="delete"){
var _32=_30;
}else{
_32=0;
for(var i in _31){
if(!isNaN(parseInt(i))){
_32=i;
}
}
_32++;
}
_31[_32]={method:_2d,id:_30,content:_2f};
return _f(_32,_31);
};
function _f(_33,_34){
var _35=_34[_33];
var _36=dojox.rpc.JsonRest.getServiceAndId(_35.id);
var _37=_2c(_35.method,_36.service,_36.id,_35.content);
_34[_33]=_35;
dojox.storage.put("dirty",_34,function(){
},_2);
_37.addBoth(function(_38){
if(_a(_38)){
return null;
}
var _39=dojox.storage.get("dirty",_2)||{};
delete _39[_33];
dojox.storage.put("dirty",_39,function(){
},_2);
return _38;
});
return _37;
};
dojo.connect(_4,"onLoad",_5);
dojo.connect(_4,"onUpdate",_5);
})();
}
