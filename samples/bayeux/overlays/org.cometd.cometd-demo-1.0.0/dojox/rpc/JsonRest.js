/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dojox.rpc.JsonRest"]){
dojo._hasResource["dojox.rpc.JsonRest"]=true;
dojo.provide("dojox.rpc.JsonRest");
dojo.require("dojox.json.ref");
dojo.require("dojox.rpc.Rest");
(function(){
var _1=[];
var _2=dojox.rpc.Rest;
var jr;
function _4(_5,_6,_7,_8){
var _9=_6.ioArgs&&_6.ioArgs.xhr&&_6.ioArgs.xhr.getResponseHeader("Last-Modified");
if(_9&&_2._timeStamps){
_2._timeStamps[_8]=_9;
}
return _7&&dojox.json.ref.resolveJson(_7,{defaultId:_8,index:_2._index,timeStamps:_9&&_2._timeStamps,time:_9,idPrefix:_5.servicePath,idAttribute:jr.getIdAttribute(_5),schemas:jr.schemas,loader:jr._loader,assignAbsoluteIds:true});
};
jr=dojox.rpc.JsonRest={conflictDateHeader:"If-Unmodified-Since",commit:function(_a){
_a=_a||{};
var _b=[];
var _c={};
var _d=[];
for(var i=0;i<_1.length;i++){
var _f=_1[i];
var _10=_f.object;
var old=_f.old;
var _12=false;
if(!(_a.service&&(_10||old)&&(_10||old).__id.indexOf(_a.service.servicePath))&&_f.save){
delete _10.__isDirty;
if(_10){
if(old){
var _13;
if((_13=_10.__id.match(/(.*)#.*/))){
_10=_2._index[_13[1]];
}
if(!(_10.__id in _c)){
_c[_10.__id]=_10;
_b.push({method:"put",target:_10,content:_10});
}
}else{
_b.push({method:"post",target:{__id:jr.getServiceAndId(_10.__id).service.servicePath},content:_10});
}
}else{
if(old){
_b.push({method:"delete",target:old});
}
}
_d.push(_f);
_1.splice(i--,1);
}
}
dojo.connect(_a,"onError",function(){
var _14=_1;
_1=_d;
var _15=0;
jr.revert();
_1=_14;
});
jr.sendToServer(_b,_a);
return _b;
},sendToServer:function(_16,_17){
var _18;
var _19=dojo.xhr;
var _1a=_16.length;
var i,_1c;
var _1d;
var _1e=this.conflictDateHeader;
dojo.xhr=function(_1f,_20){
_20.headers=_20.headers||{};
_20.headers["Transaction"]=_16.length-1==i?"commit":"open";
if(_1e&&_1d){
_20.headers[_1e]=_1d;
}
if(_1c){
_20.headers["Content-ID"]="<"+_1c+">";
}
return _19.apply(dojo,arguments);
};
for(i=0;i<_16.length;i++){
var _21=_16[i];
dojox.rpc.JsonRest._contentId=_21.content&&_21.content.__id;
var _22=_21.method=="post";
_1d=_21.method=="put"&&_2._timeStamps[_21.content.__id];
if(_1d){
_2._timeStamps[_21.content.__id]=(new Date())+"";
}
_1c=_22&&dojox.rpc.JsonRest._contentId;
var _23=jr.getServiceAndId(_21.target.__id);
var _24=_23.service;
var dfd=_21.deferred=_24[_21.method](_23.id.replace(/#/,""),dojox.json.ref.toJson(_21.content,false,_24.servicePath,true));
(function(_26,dfd,_28){
dfd.addCallback(function(_29){
try{
var _2a=dfd.ioArgs.xhr&&dfd.ioArgs.xhr.getResponseHeader("Location");
if(_2a){
var _2b=_2a.match(/(^\w+:\/\/)/)&&_2a.indexOf(_28.servicePath);
_2a=_2b>0?_2a.substring(_2b):(_28.servicePath+_2a).replace(/^(.*\/)?(\w+:\/\/)|[^\/\.]+\/\.\.\/|^.*\/(\/)/,"$2$3");
_26.__id=_2a;
_2._index[_2a]=_26;
}
_29=_4(_28,dfd,_29,_26&&_26.__id);
}
catch(e){
}
if(!(--_1a)){
if(_17.onComplete){
_17.onComplete.call(_17.scope);
}
}
return _29;
});
})(_21.content,dfd,_24);
dfd.addErrback(function(_2c){
_1a=-1;
_17.onError.call(_17.scope,_2c);
});
}
dojo.xhr=_19;
},getDirtyObjects:function(){
return _1;
},revert:function(_2d){
for(var i=_1.length;i>0;){
i--;
var _2f=_1[i];
var _30=_2f.object;
var old=_2f.old;
if(!(_2d&&(_30||old)&&(_30||old).__id.indexOf(_2d.servicePath))){
if(_30&&old){
for(var j in old){
if(old.hasOwnProperty(j)){
_30[j]=old[j];
}
}
for(j in _30){
if(!old.hasOwnProperty(j)){
delete _30[j];
}
}
}
_1.splice(i,1);
}
}
},changing:function(_33,_34){
if(!_33.__id){
return;
}
_33.__isDirty=true;
for(var i=0;i<_1.length;i++){
var _36=_1[i];
if(_33==_36.object){
if(_34){
_36.object=false;
if(!this._saveNotNeeded){
_36.save=true;
}
}
return;
}
}
var old=_33 instanceof Array?[]:{};
for(i in _33){
if(_33.hasOwnProperty(i)){
old[i]=_33[i];
}
}
_1.push({object:!_34&&_33,old:old,save:!this._saveNotNeeded});
},deleteObject:function(_38){
this.changing(_38,true);
},getConstructor:function(_39,_3a){
if(typeof _39=="string"){
var _3b=_39;
_39=new dojox.rpc.Rest(_39,true);
this.registerService(_39,_3b,_3a);
}
if(_39._constructor){
return _39._constructor;
}
_39._constructor=function(_3c){
var _3d=this;
var _3e=arguments;
var _3f;
function _40(_41){
if(_41){
_40(_41["extends"]);
_3f=_41.properties;
for(var i in _3f){
var _43=_3f[i];
if(_43&&(typeof _43=="object")&&("default" in _43)){
_3d[i]=_43["default"];
}
}
}
if(_3c){
dojo.mixin(_3d,_3c);
}
if(_41&&_41.prototype&&_41.prototype.initialize){
_41.prototype.initialize.apply(_3d,_3e);
}
};
_40(_39._schema);
var _44=jr.getIdAttribute(_39);
_2._index[this.__id=this.__clientId=_39.servicePath+(this[_44]||Math.random().toString(16).substring(2,14)+"@"+((dojox.rpc.Client&&dojox.rpc.Client.clientId)||"client"))]=this;
if(dojox.json.schema&&_3f){
dojox.json.schema.mustBeValid(dojox.json.schema.validate(this,_39._schema));
}
_1.push({object:this,save:true});
};
return dojo.mixin(_39._constructor,_39._schema,{load:_39});
},fetch:function(_45){
var _46=jr.getServiceAndId(_45);
return this.byId(_46.service,_46.id);
},getIdAttribute:function(_47){
var _48=_47._schema;
var _49;
if(_48){
if(!(_49=_48._idAttr)){
for(var i in _48.properties){
if(_48.properties[i].identity){
_48._idAttr=_49=i;
}
}
}
}
return _49||"id";
},getServiceAndId:function(_4b){
var _4c=_4b.match(/^(.*\/)([^\/]*)$/);
var svc=jr.services[_4c[1]]||new dojox.rpc.Rest(_4c[1],true);
return {service:svc,id:_4c[2]};
},services:{},schemas:{},registerService:function(_4e,_4f,_50){
_4f=_4f||_4e.servicePath;
_4f=_4e.servicePath=_4f.match(/\/$/)?_4f:(_4f+"/");
_4e._schema=jr.schemas[_4f]=_50||_4e._schema||{};
jr.services[_4f]=_4e;
},byId:function(_51,id){
var _53,_54=_2._index[(_51.servicePath||"")+id];
if(_54&&!_54._loadObject){
_53=new dojo.Deferred();
_53.callback(_54);
return _53;
}
return this.query(_51,id);
},query:function(_55,id,_57){
var _58=_55(id,_57);
_58.addCallback(function(_59){
if(_59.nodeType&&_59.cloneNode){
return _59;
}
return _4(_55,_58,_59,typeof id!="string"||(_57&&(_57.start||_57.count))?undefined:id);
});
return _58;
},_loader:function(_5a){
var _5b=jr.getServiceAndId(this.__id);
var _5c=this;
jr.query(_5b.service,_5b.id).addBoth(function(_5d){
if(_5d==_5c){
delete _5d.$ref;
delete _5d._loadObject;
}else{
_5c._loadObject=function(_5e){
_5e(_5d);
};
}
_5a(_5d);
});
},isDirty:function(_5f){
if(!_5f){
return !!_1.length;
}
return _5f.__isDirty;
}};
})();
}
