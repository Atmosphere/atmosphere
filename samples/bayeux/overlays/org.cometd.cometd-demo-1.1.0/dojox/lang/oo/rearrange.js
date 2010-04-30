/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dojox.lang.oo.rearrange"]){
dojo._hasResource["dojox.lang.oo.rearrange"]=true;
dojo.provide("dojox.lang.oo.rearrange");
(function(){
var _1=dojo._extraNames,_2=_1.length,_3=Object.prototype.toString;
dojox.lang.oo.rearrange=function(_4,_5){
var _6,_7,_8,i,t;
for(_6 in _5){
_7=_5[_6];
if(!_7||_3.call(_7)=="[object String]"){
_8=_4[_6];
if(!(_6 in empty)||empty[_6]!==_8){
if(!(delete _4[_6])){
_4[_6]=undefined;
}
if(_7){
_4[_7]=_8;
}
}
}
}
if(_2){
for(i=0;i<_2;++i){
_6=_1[i];
_7=_5[_6];
if(!_7||_3.call(_7)=="[object String]"){
_8=_4[_6];
if(!(_6 in empty)||empty[_6]!==_8){
if(!(delete _4[_6])){
_4[_6]=undefined;
}
if(_7){
_4[_7]=_8;
}
}
}
}
}
return _4;
};
})();
}
