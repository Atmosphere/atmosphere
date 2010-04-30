/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dojox.embed.Quicktime"]){
dojo._hasResource["dojox.embed.Quicktime"]=true;
dojo.provide("dojox.embed.Quicktime");
(function(d){
var _2,_3={major:0,minor:0,rev:0},_4,_5={width:320,height:240,redirect:null},_6="dojox-embed-quicktime-",_7=0,_8="This content requires the <a href=\"http://www.apple.com/quicktime/download/\" title=\"Download and install QuickTime.\">QuickTime plugin</a>.";
function _9(_a){
_a=d.mixin(d.clone(_5),_a||{});
if(!("path" in _a)&&!_a.testing){
console.error("dojox.embed.Quicktime(ctor):: no path reference to a QuickTime movie was provided.");
return null;
}
if(_a.testing){
_a.path="";
}
if(!("id" in _a)){
_a.id=_6+_7++;
}
return _a;
};
if(d.isIE){
_4=(function(){
try{
var o=new ActiveXObject("QuickTimeCheckObject.QuickTimeCheck.1");
if(o!==undefined){
var v=o.QuickTimeVersion.toString(16);
function p(i){
return (v.substring(i,i+1)-0)||0;
};
_3={major:p(0),minor:p(1),rev:p(2)};
return o.IsQuickTimeAvailable(0);
}
}
catch(e){
}
return false;
})();
_2=function(_f){
if(!_4){
return {id:null,markup:_8};
}
_f=_9(_f);
if(!_f){
return null;
}
var s="<object classid=\"clsid:02BF25D5-8C17-4B23-BC80-D3488ABDDC6B\" "+"codebase=\"http://www.apple.com/qtactivex/qtplugin.cab#version=6,0,2,0\" "+"id=\""+_f.id+"\" "+"width=\""+_f.width+"\" "+"height=\""+_f.height+"\">"+"<param name=\"src\" value=\""+_f.path+"\"/>";
for(var p in _f.params||{}){
s+="<param name=\""+p+"\" value=\""+_f.params[p]+"\"/>";
}
s+="</object>";
return {id:_f.id,markup:s};
};
}else{
_4=(function(){
for(var i=0,p=navigator.plugins,l=p.length;i<l;i++){
if(p[i].name.indexOf("QuickTime")>-1){
return true;
}
}
return false;
})();
_2=function(_15){
if(!_4){
return {id:null,markup:_8};
}
_15=_9(_15);
if(!_15){
return null;
}
var s="<embed type=\"video/quicktime\" src=\""+_15.path+"\" "+"id=\""+_15.id+"\" "+"name=\""+_15.id+"\" "+"pluginspage=\"www.apple.com/quicktime/download\" "+"enablejavascript=\"true\" "+"width=\""+_15.width+"\" "+"height=\""+_15.height+"\"";
for(var p in _15.params||{}){
s+=" "+p+"=\""+_15.params[p]+"\"";
}
s+="></embed>";
return {id:_15.id,markup:s};
};
}
dojox.embed.Quicktime=function(_18,_19){
return dojox.embed.Quicktime.place(_18,_19);
};
d.mixin(dojox.embed.Quicktime,{minSupported:6,available:_4,supported:_4,version:_3,initialized:false,onInitialize:function(){
dojox.embed.Quicktime.initialized=true;
},place:function(_1a,_1b){
var o=_2(_1a);
if(!(_1b=d.byId(_1b))){
_1b=d.create("div",{id:o.id+"-container"},d.body());
}
if(o){
_1b.innerHTML=o.markup;
if(o.id){
return d.isIE?d.byId(o.id):document[o.id];
}
}
return null;
}});
if(!d.isIE){
var id="-qt-version-test",o=_2({testing:true,width:4,height:4}),c=10,top="-1000px",_21="1px";
function _22(){
setTimeout(function(){
var qt=document[o.id],n=d.byId(id);
if(qt){
try{
var v=qt.GetQuickTimeVersion().split(".");
dojox.embed.Quicktime.version={major:parseInt(v[0]||0),minor:parseInt(v[1]||0),rev:parseInt(v[2]||0)};
if(dojox.embed.Quicktime.supported=v[0]){
dojox.embed.Quicktime.onInitialize();
}
c=0;
}
catch(e){
if(c--){
_22();
}
}
}
if(!c&&n){
d.destroy(n);
}
},20);
};
if(d._initFired){
d.create("div",{innerHTML:o.markup,id:id,style:{top:top,left:0,width:_21,height:_21,overflow:"hidden",position:"absolute"}},d.body());
}else{
document.write("<div style=\"top:"+top+";left:0;width:"+_21+";height:"+_21+";overflow:hidden;position:absolute\" id=\""+id+"\">"+o.markup+"</div>");
}
_22();
}else{
if(d.isIE&&_4){
dojox.embed.Quicktime.onInitialize();
}
}
})(dojo);
}
