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
function _3(_4,_5,_6,_7){
var _8=_5.ioArgs&&_5.ioArgs.xhr&&_5.ioArgs.xhr.getResponseHeader("Last-Modified");
if(_8&&_2._timeStamps){
_2._timeStamps[_7]=_8;
}
var _9=_4._schema&&_4._schema.hrefProperty;
if(_9){
dojox.json.ref.refAttribute=_9;
}
_6=_6&&dojox.json.ref.resolveJson(_6,{defaultId:_7,index:_2._index,timeStamps:_8&&_2._timeStamps,time:_8,idPrefix:_4.servicePath.replace(/[^\/]*$/,""),idAttribute:jr.getIdAttribute(_4),schemas:jr.schemas,loader:jr._loader,idAsRef:_4.idAsRef,assignAbsoluteIds:true});
dojox.json.ref.refAttribute="$ref";
return _6;
};
jr=dojox.rpc.JsonRest={serviceClass:dojox.rpc.Rest,conflictDateHeader:"If-Unmodified-Since",commit:function(_a){
_a=_a||{};
var _b=[];
var _c={};
var _d=[];
for(var i=0;i<_1.length;i++){
var _e=_1[i];
var _f=_e.object;
var old=_e.old;
var _10=false;
if(!(_a.service&&(_f||old)&&(_f||old).__id.indexOf(_a.service.servicePath))&&_e.save){
delete _f.__isDirty;
if(_f){
if(old){
var _11;
if((_11=_f.__id.match(/(.*)#.*/))){
_f=_2._index[_11[1]];
}
if(!(_f.__id in _c)){
_c[_f.__id]=_f;
if(_a.incrementalUpdates&&!_11){
var _12=(typeof _a.incrementalUpdates=="function"?_a.incrementalUpdates:function(){
_12={};
for(var j in _f){
if(_f.hasOwnProperty(j)){
if(_f[j]!==old[j]){
_12[j]=_f[j];
}
}else{
if(old.hasOwnProperty(j)){
return null;
}
}
}
return _12;
})(_f,old);
}
if(_12){
_b.push({method:"post",target:_f,content:_12});
}else{
_b.push({method:"put",target:_f,content:_f});
}
}
}else{
var _13=jr.getServiceAndId(_f.__id).service;
var _14=jr.getIdAttribute(_13);
if((_14 in _f)&&!_a.alwaysPostNewItems){
_b.push({method:"put",target:_f,content:_f});
}else{
_b.push({method:"post",target:{__id:_13.servicePath},content:_f});
}
}
}else{
if(old){
_b.push({method:"delete",target:old});
}
}
_d.push(_e);
_1.splice(i--,1);
}
}
dojo.connect(_a,"onError",function(){
if(_a.revertOnError!==false){
var _15=_1;
_1=_d;
var _16=0;
jr.revert();
_1=_15;
}else{
_1=dirtyObject.concat(_d);
}
});
jr.sendToServer(_b,_a);
return _b;
},sendToServer:function(_17,_18){
var _19;
var _1a=dojo.xhr;
var _1b=_17.length;
var i,_1c;
var _1d;
var _1e=this.conflictDateHeader;
dojo.xhr=function(_1f,_20){
_20.headers=_20.headers||{};
_20.headers["Transaction"]=_17.length-1==i?"commit":"open";
if(_1e&&_1d){
_20.headers[_1e]=_1d;
}
if(_1c){
_20.headers["Content-ID"]="<"+_1c+">";
}
return _1a.apply(dojo,arguments);
};
for(i=0;i<_17.length;i++){
var _21=_17[i];
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
(function(_25,dfd,_26){
dfd.addCallback(function(_27){
try{
var _28=dfd.ioArgs.xhr&&dfd.ioArgs.xhr.getResponseHeader("Location");
if(_28){
var _29=_28.match(/(^\w+:\/\/)/)&&_28.indexOf(_26.servicePath);
_28=_29>0?_28.substring(_29):(_26.servicePath+_28).replace(/^(.*\/)?(\w+:\/\/)|[^\/\.]+\/\.\.\/|^.*\/(\/)/,"$2$3");
_25.__id=_28;
_2._index[_28]=_25;
}
_27=_3(_26,dfd,_27,_25&&_25.__id);
}
catch(e){
}
if(!(--_1b)){
if(_18.onComplete){
_18.onComplete.call(_18.scope,_17);
}
}
return _27;
});
})(_21.content,dfd,_24);
dfd.addErrback(function(_2a){
_1b=-1;
_18.onError.call(_18.scope,_2a);
});
}
dojo.xhr=_1a;
},getDirtyObjects:function(){
return _1;
},revert:function(_2b){
for(var i=_1.length;i>0;){
i--;
var _2c=_1[i];
var _2d=_2c.object;
var old=_2c.old;
var _2e=dojox.data._getStoreForItem(_2d||old);
if(!(_2b&&(_2d||old)&&(_2d||old).__id.indexOf(_2b.servicePath))){
if(_2d&&old){
for(var j in old){
if(old.hasOwnProperty(j)&&_2d[j]!==old[j]){
if(_2e){
_2e.onSet(_2d,j,_2d[j],old[j]);
}
_2d[j]=old[j];
}
}
for(j in _2d){
if(!old.hasOwnProperty(j)){
if(_2e){
_2e.onSet(_2d,j,_2d[j]);
}
delete _2d[j];
}
}
}else{
if(!old){
if(_2e){
_2e.onDelete(_2d);
}
}else{
if(_2e){
_2e.onNew(old);
}
}
}
delete (_2d||old).__isDirty;
_1.splice(i,1);
}
}
},changing:function(_2f,_30){
if(!_2f.__id){
return;
}
_2f.__isDirty=true;
for(var i=0;i<_1.length;i++){
var _31=_1[i];
if(_2f==_31.object){
if(_30){
_31.object=false;
if(!this._saveNotNeeded){
_31.save=true;
}
}
return;
}
}
var old=_2f instanceof Array?[]:{};
for(i in _2f){
if(_2f.hasOwnProperty(i)){
old[i]=_2f[i];
}
}
_1.push({object:!_30&&_2f,old:old,save:!this._saveNotNeeded});
},deleteObject:function(_32){
this.changing(_32,true);
},getConstructor:function(_33,_34){
if(typeof _33=="string"){
var _35=_33;
_33=new dojox.rpc.Rest(_33,true);
this.registerService(_33,_35,_34);
}
if(_33._constructor){
return _33._constructor;
}
_33._constructor=function(_36){
var _37=this;
var _38=arguments;
var _39;
var _3a;
function _3b(_3c){
if(_3c){
_3b(_3c["extends"]);
_39=_3c.properties;
for(var i in _39){
var _3d=_39[i];
if(_3d&&(typeof _3d=="object")&&("default" in _3d)){
_37[i]=_3d["default"];
}
}
}
if(_3c&&_3c.prototype&&_3c.prototype.initialize){
_3a=true;
_3c.prototype.initialize.apply(_37,_38);
}
};
_3b(_33._schema);
if(!_3a&&_36&&typeof _36=="object"){
dojo.mixin(_37,_36);
}
var _3e=jr.getIdAttribute(_33);
_2._index[this.__id=this.__clientId=_33.servicePath+(this[_3e]||Math.random().toString(16).substring(2,14)+"@"+((dojox.rpc.Client&&dojox.rpc.Client.clientId)||"client"))]=this;
if(dojox.json.schema&&_39){
dojox.json.schema.mustBeValid(dojox.json.schema.validate(this,_33._schema));
}
_1.push({object:this,save:true});
};
return dojo.mixin(_33._constructor,_33._schema,{load:_33});
},fetch:function(_3f){
var _40=jr.getServiceAndId(_3f);
return this.byId(_40.service,_40.id);
},getIdAttribute:function(_41){
var _42=_41._schema;
var _43;
if(_42){
if(!(_43=_42._idAttr)){
for(var i in _42.properties){
if(_42.properties[i].identity||(_42.properties[i].link=="self")){
_42._idAttr=_43=i;
}
}
}
}
return _43||"id";
},getServiceAndId:function(_44){
var _45="";
for(var _46 in jr.services){
if((_44.substring(0,_46.length)==_46)&&(_46.length>=_45.length)){
_45=_46;
}
}
if(_45){
return {service:jr.services[_45],id:_44.substring(_45.length)};
}
var _47=_44.match(/^(.*\/)([^\/]*)$/);
return {service:new jr.serviceClass(_47[1],true),id:_47[2]};
},services:{},schemas:{},registerService:function(_48,_49,_4a){
_49=_48.servicePath=_49||_48.servicePath;
_48._schema=jr.schemas[_49]=_4a||_48._schema||{};
jr.services[_49]=_48;
},byId:function(_4b,id){
var _4c,_4d=_2._index[(_4b.servicePath||"")+id];
if(_4d&&!_4d._loadObject){
_4c=new dojo.Deferred();
_4c.callback(_4d);
return _4c;
}
return this.query(_4b,id);
},query:function(_4e,id,_4f){
var _50=_4e(id,_4f);
_50.addCallback(function(_51){
if(_51.nodeType&&_51.cloneNode){
return _51;
}
return _3(_4e,_50,_51,typeof id!="string"||(_4f&&(_4f.start||_4f.count))?undefined:id);
});
return _50;
},_loader:function(_52){
var _53=jr.getServiceAndId(this.__id);
var _54=this;
jr.query(_53.service,_53.id).addBoth(function(_55){
if(_55==_54){
delete _55.$ref;
delete _55._loadObject;
}else{
_54._loadObject=function(_56){
_56(_55);
};
}
_52(_55);
});
},isDirty:function(_57){
if(!_57){
return !!_1.length;
}
return _57.__isDirty;
}};
})();
}
