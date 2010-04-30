/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dijit._base.scroll"]){
dojo._hasResource["dijit._base.scroll"]=true;
dojo.provide("dijit._base.scroll");
dijit.scrollIntoView=function(_1,_2){
try{
_1=dojo.byId(_1);
var _3=_1.ownerDocument||dojo.doc,_4=_3.body||dojo.body(),_5=_3.documentElement||_4.parentNode,_6=dojo.isIE,_7=dojo.isWebKit;
if((!(dojo.isMoz||_6||_7)||_1==_4||_1==_5)&&(typeof _1.scrollIntoView!="undefined")){
_1.scrollIntoView(false);
return;
}
var _8=_3.compatMode=="BackCompat",_9=_8?_4:_5,_a=_7?_4:_9,_b=_9.clientWidth,_c=_9.clientHeight,_d=!dojo._isBodyLtr(),_e=_2||dojo.position(_1),el=_1.parentNode,_f=function(el){
return ((_6<=6||(_6&&_8))?false:(dojo.style(el,"position").toLowerCase()=="fixed"));
};
if(_f(_1)){
return;
}
while(el){
if(el==_4){
el=_a;
}
var _10=dojo.position(el),_11=_f(el);
with(_10){
if(el==_a){
w=_b,h=_c;
if(_a==_5&&_6&&_d){
x+=_a.offsetWidth-w;
}
if(x<0||!_6){
x=0;
}
if(y<0||!_6){
y=0;
}
}else{
var pb=dojo._getPadBorderExtents(el);
w-=pb.w;
h-=pb.h;
x+=pb.l;
y+=pb.t;
}
with(el){
if(el!=_a){
var _12=clientWidth,_13=w-_12;
if(_12>0&&_13>0){
w=_12;
if(_6&&_d){
x+=_13;
}
}
_12=clientHeight;
_13=h-_12;
if(_12>0&&_13>0){
h=_12;
}
}
if(_11){
if(y<0){
h+=y,y=0;
}
if(x<0){
w+=x,x=0;
}
if(y+h>_c){
h=_c-y;
}
if(x+w>_b){
w=_b-x;
}
}
var l=_e.x-x,t=_e.y-Math.max(y,0),r=l+_e.w-w,bot=t+_e.h-h;
if(r*l>0){
var s=Math[l<0?"max":"min"](l,r);
_e.x+=scrollLeft;
scrollLeft+=(_6>=8&&!_8&&_d)?-s:s;
_e.x-=scrollLeft;
}
if(bot*t>0){
_e.y+=scrollTop;
scrollTop+=Math[t<0?"max":"min"](t,bot);
_e.y-=scrollTop;
}
}
}
el=(el!=_a)&&!_11&&el.parentNode;
}
}
catch(error){
console.error("scrollIntoView: "+error);
_1.scrollIntoView(false);
}
};
}
