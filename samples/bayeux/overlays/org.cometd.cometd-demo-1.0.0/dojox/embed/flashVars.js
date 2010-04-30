/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dojox.embed.flashVars"]){
dojo._hasResource["dojox.embed.flashVars"]=true;
dojo.provide("dojox.embed.flashVars");
dojo.mixin(dojox.embed.flashVars,{serialize:function(n,o){
var _3=function(_4){
if(typeof _4=="string"){
_4=_4.replace(/;/g,"_sc_");
_4=_4.replace(/\./g,"_pr_");
_4=_4.replace(/\:/g,"_cl_");
}
return _4;
};
var df=dojox.embed.flashVars.serialize;
var _6="";
if(dojo.isArray(o)){
for(var i=0;i<o.length;i++){
_6+=df(n+"."+i,_3(o[i]))+";";
}
return _6.replace(/;{2,}/g,";");
}else{
if(dojo.isObject(o)){
for(var nm in o){
_6+=df(n+"."+nm,_3(o[nm]))+";";
}
return _6.replace(/;{2,}/g,";");
}
}
return n+":"+o;
}});
}
