/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dojox.rpc.Rest"]){
dojo._hasResource["dojox.rpc.Rest"]=true;
dojo.provide("dojox.rpc.Rest");
(function(){
if(dojox.rpc&&dojox.rpc.transportRegistry){
dojox.rpc.transportRegistry.register("REST",function(_1){
return _1=="REST";
},{getExecutor:function(_2,_3,_4){
return new dojox.rpc.Rest(_3.name,(_3.contentType||_4._smd.contentType||"").match(/json|javascript/),null,function(id,_5){
var _6=_4._getRequest(_3,[id]);
_6.url=_6.target+(_6.data?"?"+_6.data:"");
return _6;
});
}});
}
var _7;
function _8(_9,_a,_b,id){
_9.addCallback(function(_c){
if(_9.ioArgs.xhr&&_b){
_b=_9.ioArgs.xhr.getResponseHeader("Content-Range");
_9.fullLength=_b&&(_b=_b.match(/\/(.*)/))&&parseInt(_b[1]);
}
return _c;
});
return _9;
};
_7=dojox.rpc.Rest=function(_d,_e,_f,_10){
var _11;
_11=function(id,_12){
return _7._get(_11,id,_12);
};
_11.isJson=_e;
_11._schema=_f;
_11.cache={serialize:_e?((dojox.json&&dojox.json.ref)||dojo).toJson:function(_13){
return _13;
}};
_11._getRequest=_10||function(id,_14){
if(dojo.isObject(id)){
id=dojo.objectToQuery(id);
id=id?"?"+id:"";
}
if(_14&&_14.sort&&!_14.queryStr){
id+=(id?"&":"?")+"sort(";
for(var i=0;i<_14.sort.length;i++){
var _15=_14.sort[i];
id+=(i>0?",":"")+(_15.descending?"-":"+")+encodeURIComponent(_15.attribute);
}
id+=")";
}
var _16={url:_d+(id==null?"":id),handleAs:_e?"json":"text",contentType:_e?"application/json":"text/plain",sync:dojox.rpc._sync,headers:{Accept:_e?"application/json,application/javascript":"*/*"}};
if(_14&&(_14.start>=0||_14.count>=0)){
_16.headers.Range="items="+(_14.start||"0")+"-"+((_14.count&&_14.count!=Infinity&&(_14.count+(_14.start||0)-1))||"");
}
dojox.rpc._sync=false;
return _16;
};
function _17(_18){
_11[_18]=function(id,_19){
return _7._change(_18,_11,id,_19);
};
};
_17("put");
_17("post");
_17("delete");
_11.servicePath=_d;
return _11;
};
_7._index={};
_7._timeStamps={};
_7._change=function(_1a,_1b,id,_1c){
var _1d=_1b._getRequest(id);
_1d[_1a+"Data"]=_1c;
return _8(dojo.xhr(_1a.toUpperCase(),_1d,true),_1b);
};
_7._get=function(_1e,id,_1f){
_1f=_1f||{};
return _8(dojo.xhrGet(_1e._getRequest(id,_1f)),_1e,(_1f.start>=0||_1f.count>=0),id);
};
})();
}
