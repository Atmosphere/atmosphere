/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dojox.gfx.arc"]){
dojo._hasResource["dojox.gfx.arc"]=true;
dojo.provide("dojox.gfx.arc");
dojo.require("dojox.gfx.matrix");
(function(){
var m=dojox.gfx.matrix,_1=function(_2){
var _3=Math.cos(_2),_4=Math.sin(_2),p2={x:_3+(4/3)*(1-_3),y:_4-(4/3)*_3*(1-_3)/_4};
return {s:{x:_3,y:-_4},c1:{x:p2.x,y:-p2.y},c2:p2,e:{x:_3,y:_4}};
},_5=2*Math.PI,_6=Math.PI/4,_7=Math.PI/8,_8=_6+_7,_9=_1(_7);
dojo.mixin(dojox.gfx.arc,{unitArcAsBezier:_1,curvePI4:_9,arcAsBezier:function(_a,rx,ry,_b,_c,_d,x,y){
_c=Boolean(_c);
_d=Boolean(_d);
var _e=m._degToRad(_b),_f=rx*rx,ry2=ry*ry,pa=m.multiplyPoint(m.rotate(-_e),{x:(_a.x-x)/2,y:(_a.y-y)/2}),_10=pa.x*pa.x,_11=pa.y*pa.y,c1=Math.sqrt((_f*ry2-_f*_11-ry2*_10)/(_f*_11+ry2*_10));
if(isNaN(c1)){
c1=0;
}
var ca={x:c1*rx*pa.y/ry,y:-c1*ry*pa.x/rx};
if(_c==_d){
ca={x:-ca.x,y:-ca.y};
}
var c=m.multiplyPoint([m.translate((_a.x+x)/2,(_a.y+y)/2),m.rotate(_e)],ca);
var _12=m.normalize([m.translate(c.x,c.y),m.rotate(_e),m.scale(rx,ry)]);
var _13=m.invert(_12),sp=m.multiplyPoint(_13,_a),ep=m.multiplyPoint(_13,x,y),_14=Math.atan2(sp.y,sp.x),_15=Math.atan2(ep.y,ep.x),_16=_14-_15;
if(_d){
_16=-_16;
}
if(_16<0){
_16+=_5;
}else{
if(_16>_5){
_16-=_5;
}
}
var _17=_7,_18=_9,_19=_d?_17:-_17,_1a=[];
for(var _1b=_16;_1b>0;_1b-=_6){
if(_1b<_8){
_17=_1b/2;
_18=_1(_17);
_19=_d?_17:-_17;
_1b=0;
}
var c1,c2,e,M=m.normalize([_12,m.rotate(_14+_19)]);
if(_d){
c1=m.multiplyPoint(M,_18.c1);
c2=m.multiplyPoint(M,_18.c2);
e=m.multiplyPoint(M,_18.e);
}else{
c1=m.multiplyPoint(M,_18.c2);
c2=m.multiplyPoint(M,_18.c1);
e=m.multiplyPoint(M,_18.s);
}
_1a.push([c1.x,c1.y,c2.x,c2.y,e.x,e.y]);
_14+=2*_19;
}
return _1a;
}});
})();
}
