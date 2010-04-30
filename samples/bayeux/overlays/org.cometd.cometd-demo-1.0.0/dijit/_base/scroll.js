/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dijit._base.scroll"]){
dojo._hasResource["dijit._base.scroll"]=true;
dojo.provide("dijit._base.scroll");
dijit.scrollIntoView=function(_1){
try{
_1=dojo.byId(_1);
var _2=dojo.doc;
var _3=dojo.body();
var _4=_3.parentNode;
if((!(dojo.isFF>=3||dojo.isIE||dojo.isWebKit)||_1==_3||_1==_4)&&(typeof _1.scrollIntoView=="function")){
_1.scrollIntoView(false);
return;
}
var _5=dojo._isBodyLtr();
var _6=dojo.isIE>=8&&!_7;
var _8=!_5&&!_6;
var _9=_3;
var _7=_2.compatMode=="BackCompat";
if(_7){
_4._offsetWidth=_4._clientWidth=_3._offsetWidth=_3.clientWidth;
_4._offsetHeight=_4._clientHeight=_3._offsetHeight=_3.clientHeight;
}else{
if(dojo.isWebKit){
_3._offsetWidth=_3._clientWidth=_4.clientWidth;
_3._offsetHeight=_3._clientHeight=_4.clientHeight;
}else{
_9=_4;
}
_4._offsetHeight=_4.clientHeight;
_4._offsetWidth=_4.clientWidth;
}
function _a(_b){
var ie=dojo.isIE;
return ((ie<=6||(ie>=7&&_7))?false:(dojo.style(_b,"position").toLowerCase()=="fixed"));
};
function _d(_e){
var _f=_e.parentNode;
var _10=_e.offsetParent;
if(_10==null||_a(_e)){
_10=_4;
_f=(_e==_3)?_4:null;
}
_e._offsetParent=_10;
_e._parent=_f;
var bp=dojo._getBorderExtents(_e);
_e._borderStart={H:(_6&&!_5)?(bp.w-bp.l):bp.l,V:bp.t};
_e._borderSize={H:bp.w,V:bp.h};
_e._scrolledAmount={H:_e.scrollLeft,V:_e.scrollTop};
_e._offsetSize={H:_e._offsetWidth||_e.offsetWidth,V:_e._offsetHeight||_e.offsetHeight};
_e._offsetStart={H:(_6&&!_5)?_10.clientWidth-_e.offsetLeft-_e._offsetSize.H:_e.offsetLeft,V:_e.offsetTop};
_e._clientSize={H:_e._clientWidth||_e.clientWidth,V:_e._clientHeight||_e.clientHeight};
if(_e!=_3&&_e!=_4&&_e!=_1){
for(var dir in _e._offsetSize){
var _13=_e._offsetSize[dir]-_e._clientSize[dir]-_e._borderSize[dir];
var _14=_e._clientSize[dir]>0&&_13>0;
if(_14){
_e._offsetSize[dir]-=_13;
if(dojo.isIE&&_8&&dir=="H"){
_e._offsetStart[dir]+=_13;
}
}
}
}
};
var _15=_1;
while(_15!=null){
if(_a(_15)){
_1.scrollIntoView(false);
return;
}
_d(_15);
_15=_15._parent;
}
if(dojo.isIE&&_1._parent){
var _16=_1._offsetParent;
_1._offsetStart.H+=_16._borderStart.H;
_1._offsetStart.V+=_16._borderStart.V;
}
if(dojo.isIE>=7&&_9==_4&&_8&&_3._offsetStart&&_3._offsetStart.H==0){
var _17=_4.scrollWidth-_4._offsetSize.H;
if(_17>0){
_3._offsetStart.H=-_17;
}
}
if(dojo.isIE<=6&&!_7){
_4._offsetSize.H+=_4._borderSize.H;
_4._offsetSize.V+=_4._borderSize.V;
}
if(_8&&_3._offsetStart&&_9==_4&&_4._scrolledAmount){
var ofs=_3._offsetStart.H;
if(ofs<0){
_4._scrolledAmount.H+=ofs;
_3._offsetStart.H=0;
}
}
_15=_1;
while(_15){
var _19=_15._parent;
if(!_19){
break;
}
if(_19.tagName=="TD"){
var _1a=_19._parent._parent._parent;
if(_19!=_15._offsetParent&&_19._offsetParent!=_15._offsetParent){
_19=_1a;
}
}
var _1b=_15._offsetParent==_19;
for(var dir in _15._offsetStart){
var _1d=dir=="H"?"V":"H";
if(_8&&dir=="H"&&(_19!=_4)&&(_19!=_3)&&(dojo.isIE||dojo.isWebKit)&&_19._clientSize.H>0&&_19.scrollWidth>_19._clientSize.H){
var _1e=_19.scrollWidth-_19._clientSize.H;
if(_1e>0){
_19._scrolledAmount.H-=_1e;
}
}
if(_19._offsetParent.tagName=="TABLE"){
if(dojo.isIE){
_19._offsetStart[dir]-=_19._offsetParent._borderStart[dir];
_19._borderStart[dir]=_19._borderSize[dir]=0;
}else{
_19._offsetStart[dir]+=_19._offsetParent._borderStart[dir];
}
}
if(dojo.isIE){
_19._offsetStart[dir]+=_19._offsetParent._borderStart[dir];
}
var _1f=_15._offsetStart[dir]-_19._scrolledAmount[dir]-(_1b?0:_19._offsetStart[dir])-_19._borderStart[dir];
var _20=_1f+_15._offsetSize[dir]-_19._offsetSize[dir]+_19._borderSize[dir];
var _21=(dir=="H")?"scrollLeft":"scrollTop";
var _22=dir=="H"&&_8;
var _23=_22?-_20:_1f;
var _24=_22?-_1f:_20;
var _25=(_23*_24<=0)?0:Math[(_23<0)?"max":"min"](_23,_24);
if(_25!=0){
var _26=_19[_21];
_19[_21]+=(_22)?-_25:_25;
var _27=_19[_21]-_26;
}
if(_1b){
_15._offsetStart[dir]+=_19._offsetStart[dir];
}
_15._offsetStart[dir]-=_19[_21];
}
_15._parent=_19._parent;
_15._offsetParent=_19._offsetParent;
}
_19=_1;
var _28;
while(_19&&_19.removeAttribute){
_28=_19.parentNode;
_19.removeAttribute("_offsetParent");
_19.removeAttribute("_parent");
_19=_28;
}
}
catch(error){
console.error("scrollIntoView: "+error);
_1.scrollIntoView(false);
}
};
}
