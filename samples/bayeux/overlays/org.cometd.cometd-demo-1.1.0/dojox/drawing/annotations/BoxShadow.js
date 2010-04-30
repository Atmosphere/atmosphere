/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dojox.drawing.annotations.BoxShadow"]){
dojo._hasResource["dojox.drawing.annotations.BoxShadow"]=true;
dojo.provide("dojox.drawing.annotations.BoxShadow");
dojox.drawing.annotations.BoxShadow=dojox.drawing.util.oo.declare(function(_1){
this.stencil=_1.stencil;
this.util=_1.stencil.util;
this.mouse=_1.stencil.mouse;
this.style=_1.stencil.style;
var _2={size:6,mult:4,alpha:0.05,place:"BR",color:"#646464"};
delete _1.stencil;
this.options=dojo.mixin(_2,_1);
this.options.color=new dojo.Color(this.options.color);
this.options.color.a=this.options.alpha;
switch(this.stencil.shortType){
case "image":
case "rect":
this.method="createForRect";
break;
case "ellipse":
this.method="createForEllipse";
break;
case "line":
this.method="createForLine";
break;
case "path":
this.method="createForPath";
break;
default:
console.warn("A shadow cannot be made for Stencil type ",this.stencil.type);
}
if(this.method){
this.render();
this.stencil.connectMult([[this.stencil,"onTransform",this,"onTransform"],[this.stencil,"render",this,"onRender"],[this.stencil,"onDelete",this,"destroy"]]);
}
},{showing:true,render:function(){
if(this.container){
this.container.removeShape();
}
this.container=this.stencil.container.createGroup();
this.container.moveToBack();
var o=this.options,_3=o.size,_4=o.mult,d=this.method=="createForPath"?this.stencil.points:this.stencil.data,r=d.r||1,p=o.place,c=o.color;
this[this.method](o,_3,_4,d,r,p,c);
},hide:function(){
if(this.showing){
this.showing=false;
this.container.removeShape();
}
},show:function(){
if(!this.showing){
this.showing=true;
this.stencil.container.add(this.container);
}
},createForPath:function(o,_5,_6,_7,r,p,c){
var sh=_5*_6/4,_8=/B/.test(p)?sh:/T/.test(p)?sh*-1:0,_9=/R/.test(p)?sh:/L/.test(p)?sh*-1:0;
var _a=true;
for(var i=1;i<=_5;i++){
var _b=i*_6;
if(dojox.gfx.renderer=="svg"){
var _c=[];
dojo.forEach(_7,function(o,i){
if(i==0){
_c.push("M "+(o.x+_9)+" "+(o.y+_8));
}else{
var _d=o.t||"L ";
_c.push(_d+(o.x+_9)+" "+(o.y+_8));
}
},this);
if(_a){
_c.push("Z");
}
this.container.createPath(_c.join(", ")).setStroke({width:_b,color:c,cap:"round"});
}else{
var _e=this.container.createPath({}).setStroke({width:_b,color:c,cap:"round"});
dojo.forEach(this.points,function(o,i){
if(i==0||o.t=="M"){
_e.moveTo(o.x+_9,o.y+_8);
}else{
if(o.t=="Z"){
_a&&_e.closePath();
}else{
_e.lineTo(o.x+_9,o.y+_8);
}
}
},this);
_a&&_e.closePath();
}
}
},createForLine:function(o,_f,_10,d,r,p,c){
var sh=_f*_10/4,shy=/B/.test(p)?sh:/T/.test(p)?sh*-1:0,shx=/R/.test(p)?sh:/L/.test(p)?sh*-1:0;
for(var i=1;i<=_f;i++){
var _11=i*_10;
this.container.createLine({x1:d.x1+shx,y1:d.y1+shy,x2:d.x2+shx,y2:d.y2+shy}).setStroke({width:_11,color:c,cap:"round"});
}
},createForEllipse:function(o,_12,_13,d,r,p,c){
var sh=_12*_13/8,shy=/B/.test(p)?sh:/T/.test(p)?sh*-1:0,shx=/R/.test(p)?sh*0.8:/L/.test(p)?sh*-0.8:0;
for(var i=1;i<=_12;i++){
var _14=i*_13;
this.container.createEllipse({cx:d.cx+shx,cy:d.cy+shy,rx:d.rx-sh,ry:d.ry-sh,r:r}).setStroke({width:_14,color:c});
}
},createForRect:function(o,_15,_16,d,r,p,c){
var sh=_15*_16/2,shy=/B/.test(p)?sh:/T/.test(p)?0:sh/2,shx=/R/.test(p)?sh:/L/.test(p)?0:sh/2;
for(var i=1;i<=_15;i++){
var _17=i*_16;
this.container.createRect({x:d.x+shx,y:d.y+shy,width:d.width-sh,height:d.height-sh,r:r}).setStroke({width:_17,color:c});
}
},onTransform:function(){
this.render();
},onRender:function(){
this.container.moveToBack();
},destroy:function(){
if(this.container){
this.container.removeShape();
}
}});
}
