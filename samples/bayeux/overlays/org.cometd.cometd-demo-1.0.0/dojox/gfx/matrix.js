/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dojox.gfx.matrix"]){
dojo._hasResource["dojox.gfx.matrix"]=true;
dojo.provide("dojox.gfx.matrix");
(function(){
var m=dojox.gfx.matrix;
var _2={};
m._degToRad=function(_3){
return _2[_3]||(_2[_3]=(Math.PI*_3/180));
};
m._radToDeg=function(_4){
return _4/Math.PI*180;
};
m.Matrix2D=function(_5){
if(_5){
if(typeof _5=="number"){
this.xx=this.yy=_5;
}else{
if(_5 instanceof Array){
if(_5.length>0){
var _6=m.normalize(_5[0]);
for(var i=1;i<_5.length;++i){
var l=_6,r=dojox.gfx.matrix.normalize(_5[i]);
_6=new m.Matrix2D();
_6.xx=l.xx*r.xx+l.xy*r.yx;
_6.xy=l.xx*r.xy+l.xy*r.yy;
_6.yx=l.yx*r.xx+l.yy*r.yx;
_6.yy=l.yx*r.xy+l.yy*r.yy;
_6.dx=l.xx*r.dx+l.xy*r.dy+l.dx;
_6.dy=l.yx*r.dx+l.yy*r.dy+l.dy;
}
dojo.mixin(this,_6);
}
}else{
dojo.mixin(this,_5);
}
}
}
};
dojo.extend(m.Matrix2D,{xx:1,xy:0,yx:0,yy:1,dx:0,dy:0});
dojo.mixin(m,{identity:new m.Matrix2D(),flipX:new m.Matrix2D({xx:-1}),flipY:new m.Matrix2D({yy:-1}),flipXY:new m.Matrix2D({xx:-1,yy:-1}),translate:function(a,b){
if(arguments.length>1){
return new m.Matrix2D({dx:a,dy:b});
}
return new m.Matrix2D({dx:a.x,dy:a.y});
},scale:function(a,b){
if(arguments.length>1){
return new m.Matrix2D({xx:a,yy:b});
}
if(typeof a=="number"){
return new m.Matrix2D({xx:a,yy:a});
}
return new m.Matrix2D({xx:a.x,yy:a.y});
},rotate:function(_e){
var c=Math.cos(_e);
var s=Math.sin(_e);
return new m.Matrix2D({xx:c,xy:-s,yx:s,yy:c});
},rotateg:function(_11){
return m.rotate(m._degToRad(_11));
},skewX:function(_12){
return new m.Matrix2D({xy:Math.tan(_12)});
},skewXg:function(_13){
return m.skewX(m._degToRad(_13));
},skewY:function(_14){
return new m.Matrix2D({yx:Math.tan(_14)});
},skewYg:function(_15){
return m.skewY(m._degToRad(_15));
},reflect:function(a,b){
if(arguments.length==1){
b=a.y;
a=a.x;
}
var a2=a*a,b2=b*b,n2=a2+b2,xy=2*a*b/n2;
return new m.Matrix2D({xx:2*a2/n2-1,xy:xy,yx:xy,yy:2*b2/n2-1});
},project:function(a,b){
if(arguments.length==1){
b=a.y;
a=a.x;
}
var a2=a*a,b2=b*b,n2=a2+b2,xy=a*b/n2;
return new m.Matrix2D({xx:a2/n2,xy:xy,yx:xy,yy:b2/n2});
},normalize:function(_22){
return (_22 instanceof m.Matrix2D)?_22:new m.Matrix2D(_22);
},clone:function(_23){
var obj=new m.Matrix2D();
for(var i in _23){
if(typeof (_23[i])=="number"&&typeof (obj[i])=="number"&&obj[i]!=_23[i]){
obj[i]=_23[i];
}
}
return obj;
},invert:function(_26){
var M=m.normalize(_26),D=M.xx*M.yy-M.xy*M.yx,M=new m.Matrix2D({xx:M.yy/D,xy:-M.xy/D,yx:-M.yx/D,yy:M.xx/D,dx:(M.xy*M.dy-M.yy*M.dx)/D,dy:(M.yx*M.dx-M.xx*M.dy)/D});
return M;
},_multiplyPoint:function(_29,x,y){
return {x:_29.xx*x+_29.xy*y+_29.dx,y:_29.yx*x+_29.yy*y+_29.dy};
},multiplyPoint:function(_2c,a,b){
var M=m.normalize(_2c);
if(typeof a=="number"&&typeof b=="number"){
return m._multiplyPoint(M,a,b);
}
return m._multiplyPoint(M,a.x,a.y);
},multiply:function(_30){
var M=m.normalize(_30);
for(var i=1;i<arguments.length;++i){
var l=M,r=m.normalize(arguments[i]);
M=new m.Matrix2D();
M.xx=l.xx*r.xx+l.xy*r.yx;
M.xy=l.xx*r.xy+l.xy*r.yy;
M.yx=l.yx*r.xx+l.yy*r.yx;
M.yy=l.yx*r.xy+l.yy*r.yy;
M.dx=l.xx*r.dx+l.xy*r.dy+l.dx;
M.dy=l.yx*r.dx+l.yy*r.dy+l.dy;
}
return M;
},_sandwich:function(_35,x,y){
return m.multiply(m.translate(x,y),_35,m.translate(-x,-y));
},scaleAt:function(a,b,c,d){
switch(arguments.length){
case 4:
return m._sandwich(m.scale(a,b),c,d);
case 3:
if(typeof c=="number"){
return m._sandwich(m.scale(a),b,c);
}
return m._sandwich(m.scale(a,b),c.x,c.y);
}
return m._sandwich(m.scale(a),b.x,b.y);
},rotateAt:function(_3c,a,b){
if(arguments.length>2){
return m._sandwich(m.rotate(_3c),a,b);
}
return m._sandwich(m.rotate(_3c),a.x,a.y);
},rotategAt:function(_3f,a,b){
if(arguments.length>2){
return m._sandwich(m.rotateg(_3f),a,b);
}
return m._sandwich(m.rotateg(_3f),a.x,a.y);
},skewXAt:function(_42,a,b){
if(arguments.length>2){
return m._sandwich(m.skewX(_42),a,b);
}
return m._sandwich(m.skewX(_42),a.x,a.y);
},skewXgAt:function(_45,a,b){
if(arguments.length>2){
return m._sandwich(m.skewXg(_45),a,b);
}
return m._sandwich(m.skewXg(_45),a.x,a.y);
},skewYAt:function(_48,a,b){
if(arguments.length>2){
return m._sandwich(m.skewY(_48),a,b);
}
return m._sandwich(m.skewY(_48),a.x,a.y);
},skewYgAt:function(_4b,a,b){
if(arguments.length>2){
return m._sandwich(m.skewYg(_4b),a,b);
}
return m._sandwich(m.skewYg(_4b),a.x,a.y);
}});
})();
dojox.gfx.Matrix2D=dojox.gfx.matrix.Matrix2D;
}
