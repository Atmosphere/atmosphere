/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dojox.gfx.canvas"]){
dojo._hasResource["dojox.gfx.canvas"]=true;
dojo.provide("dojox.gfx.canvas");
dojo.require("dojox.gfx._base");
dojo.require("dojox.gfx.shape");
dojo.require("dojox.gfx.path");
dojo.require("dojox.gfx.arc");
dojo.require("dojox.gfx.decompose");
dojo.experimental("dojox.gfx.canvas");
(function(){
var d=dojo,g=dojox.gfx,gs=g.shape,ga=g.arc,m=g.matrix,mp=m.multiplyPoint,pi=Math.PI,_1=2*pi,_2=pi/2;
d.extend(g.Shape,{_render:function(_3){
_3.save();
this._renderTransform(_3);
this._renderShape(_3);
this._renderFill(_3,true);
this._renderStroke(_3,true);
_3.restore();
},_renderTransform:function(_4){
if("canvasTransform" in this){
var t=this.canvasTransform;
_4.translate(t.dx,t.dy);
_4.rotate(t.angle2);
_4.scale(t.sx,t.sy);
_4.rotate(t.angle1);
}
},_renderShape:function(_5){
},_renderFill:function(_6,_7){
if("canvasFill" in this){
if("canvasFillImage" in this){
this.canvasFill=_6.createPattern(this.canvasFillImage,"repeat");
delete this.canvasFillImage;
}
_6.fillStyle=this.canvasFill;
if(_7){
_6.fill();
}
}else{
_6.fillStyle="rgba(0,0,0,0.0)";
}
},_renderStroke:function(_8,_9){
var s=this.strokeStyle;
if(s){
_8.strokeStyle=s.color.toString();
_8.lineWidth=s.width;
_8.lineCap=s.cap;
if(typeof s.join=="number"){
_8.lineJoin="miter";
_8.miterLimit=s.join;
}else{
_8.lineJoin=s.join;
}
if(_9){
_8.stroke();
}
}else{
if(!_9){
_8.strokeStyle="rgba(0,0,0,0.0)";
}
}
},getEventSource:function(){
return null;
},connect:function(){
},disconnect:function(){
}});
var _a=function(_b,_c,_d){
var _e=_b.prototype[_c];
_b.prototype[_c]=_d?function(){
this.surface.makeDirty();
_e.apply(this,arguments);
_d.call(this);
return this;
}:function(){
this.surface.makeDirty();
return _e.apply(this,arguments);
};
};
_a(g.Shape,"setTransform",function(){
if(this.matrix){
this.canvasTransform=g.decompose(this.matrix);
}else{
delete this.canvasTransform;
}
});
_a(g.Shape,"setFill",function(){
var fs=this.fillStyle,f;
if(fs){
if(typeof (fs)=="object"&&"type" in fs){
var _f=this.surface.rawNode.getContext("2d");
switch(fs.type){
case "linear":
case "radial":
f=fs.type=="linear"?_f.createLinearGradient(fs.x1,fs.y1,fs.x2,fs.y2):_f.createRadialGradient(fs.cx,fs.cy,0,fs.cx,fs.cy,fs.r);
d.forEach(fs.colors,function(_10){
f.addColorStop(_10.offset,g.normalizeColor(_10.color).toString());
});
break;
case "pattern":
var img=new Image(fs.width,fs.height);
this.surface.downloadImage(img,fs.src);
this.canvasFillImage=img;
}
}else{
f=fs.toString();
}
this.canvasFill=f;
}else{
delete this.canvasFill;
}
});
_a(g.Shape,"setStroke");
_a(g.Shape,"setShape");
dojo.declare("dojox.gfx.Group",g.Shape,{constructor:function(){
gs.Container._init.call(this);
},_render:function(ctx){
ctx.save();
this._renderTransform(ctx);
this._renderFill(ctx);
this._renderStroke(ctx);
for(var i=0;i<this.children.length;++i){
this.children[i]._render(ctx);
}
ctx.restore();
}});
dojo.declare("dojox.gfx.Rect",gs.Rect,{_renderShape:function(ctx){
var s=this.shape,r=Math.min(s.r,s.height/2,s.width/2),xl=s.x,xr=xl+s.width,yt=s.y,yb=yt+s.height,xl2=xl+r,xr2=xr-r,yt2=yt+r,yb2=yb-r;
ctx.beginPath();
ctx.moveTo(xl2,yt);
if(r){
ctx.arc(xr2,yt2,r,-_2,0,false);
ctx.arc(xr2,yb2,r,0,_2,false);
ctx.arc(xl2,yb2,r,_2,pi,false);
ctx.arc(xl2,yt2,r,pi,pi+_2,false);
}else{
ctx.lineTo(xr2,yt);
ctx.lineTo(xr,yb2);
ctx.lineTo(xl2,yb);
ctx.lineTo(xl,yt2);
}
ctx.closePath();
}});
var _11=[];
(function(){
var u=ga.curvePI4;
_11.push(u.s,u.c1,u.c2,u.e);
for(var a=45;a<360;a+=45){
var r=m.rotateg(a);
_11.push(mp(r,u.c1),mp(r,u.c2),mp(r,u.e));
}
})();
dojo.declare("dojox.gfx.Ellipse",gs.Ellipse,{setShape:function(){
g.Ellipse.superclass.setShape.apply(this,arguments);
var s=this.shape,t,c1,c2,r=[],M=m.normalize([m.translate(s.cx,s.cy),m.scale(s.rx,s.ry)]);
t=mp(M,_11[0]);
r.push([t.x,t.y]);
for(var i=1;i<_11.length;i+=3){
c1=mp(M,_11[i]);
c2=mp(M,_11[i+1]);
t=mp(M,_11[i+2]);
r.push([c1.x,c1.y,c2.x,c2.y,t.x,t.y]);
}
this.canvasEllipse=r;
return this;
},_renderShape:function(ctx){
var r=this.canvasEllipse;
ctx.beginPath();
ctx.moveTo.apply(ctx,r[0]);
for(var i=1;i<r.length;++i){
ctx.bezierCurveTo.apply(ctx,r[i]);
}
ctx.closePath();
}});
dojo.declare("dojox.gfx.Circle",gs.Circle,{_renderShape:function(ctx){
var s=this.shape;
ctx.beginPath();
ctx.arc(s.cx,s.cy,s.r,0,_1,1);
}});
dojo.declare("dojox.gfx.Line",gs.Line,{_renderShape:function(ctx){
var s=this.shape;
ctx.beginPath();
ctx.moveTo(s.x1,s.y1);
ctx.lineTo(s.x2,s.y2);
}});
dojo.declare("dojox.gfx.Polyline",gs.Polyline,{setShape:function(){
g.Polyline.superclass.setShape.apply(this,arguments);
var p=this.shape.points,f=p[0],r=[],c,i;
if(p.length){
if(typeof f=="number"){
r.push(f,p[1]);
i=2;
}else{
r.push(f.x,f.y);
i=1;
}
for(;i<p.length;++i){
c=p[i];
if(typeof c=="number"){
r.push(c,p[++i]);
}else{
r.push(c.x,c.y);
}
}
}
this.canvasPolyline=r;
return this;
},_renderShape:function(ctx){
var p=this.canvasPolyline;
if(p.length){
ctx.beginPath();
ctx.moveTo(p[0],p[1]);
for(var i=2;i<p.length;i+=2){
ctx.lineTo(p[i],p[i+1]);
}
}
}});
dojo.declare("dojox.gfx.Image",gs.Image,{setShape:function(){
g.Image.superclass.setShape.apply(this,arguments);
var img=new Image();
this.surface.downloadImage(img,this.shape.src);
this.canvasImage=img;
return this;
},_renderShape:function(ctx){
var s=this.shape;
ctx.drawImage(this.canvasImage,s.x,s.y,s.width,s.height);
}});
dojo.declare("dojox.gfx.Text",gs.Text,{_renderShape:function(ctx){
var s=this.shape;
}});
_a(g.Text,"setFont");
var _12={M:"_moveToA",m:"_moveToR",L:"_lineToA",l:"_lineToR",H:"_hLineToA",h:"_hLineToR",V:"_vLineToA",v:"_vLineToR",C:"_curveToA",c:"_curveToR",S:"_smoothCurveToA",s:"_smoothCurveToR",Q:"_qCurveToA",q:"_qCurveToR",T:"_qSmoothCurveToA",t:"_qSmoothCurveToR",A:"_arcTo",a:"_arcTo",Z:"_closePath",z:"_closePath"};
dojo.declare("dojox.gfx.Path",g.path.Path,{constructor:function(){
this.lastControl={};
},setShape:function(){
this.canvasPath=[];
return g.Path.superclass.setShape.apply(this,arguments);
},_updateWithSegment:function(_13){
var _14=d.clone(this.last);
this[_12[_13.action]](this.canvasPath,_13.action,_13.args);
this.last=_14;
g.Path.superclass._updateWithSegment.apply(this,arguments);
},_renderShape:function(ctx){
var r=this.canvasPath;
ctx.beginPath();
for(var i=0;i<r.length;i+=2){
ctx[r[i]].apply(ctx,r[i+1]);
}
},_moveToA:function(_15,_16,_17){
_15.push("moveTo",[_17[0],_17[1]]);
for(var i=2;i<_17.length;i+=2){
_15.push("lineTo",[_17[i],_17[i+1]]);
}
this.last.x=_17[_17.length-2];
this.last.y=_17[_17.length-1];
this.lastControl={};
},_moveToR:function(_18,_19,_1a){
if("x" in this.last){
_18.push("moveTo",[this.last.x+=_1a[0],this.last.y+=_1a[1]]);
}else{
_18.push("moveTo",[this.last.x=_1a[0],this.last.y=_1a[1]]);
}
for(var i=2;i<_1a.length;i+=2){
_18.push("lineTo",[this.last.x+=_1a[i],this.last.y+=_1a[i+1]]);
}
this.lastControl={};
},_lineToA:function(_1b,_1c,_1d){
for(var i=0;i<_1d.length;i+=2){
_1b.push("lineTo",[_1d[i],_1d[i+1]]);
}
this.last.x=_1d[_1d.length-2];
this.last.y=_1d[_1d.length-1];
this.lastControl={};
},_lineToR:function(_1e,_1f,_20){
for(var i=0;i<_20.length;i+=2){
_1e.push("lineTo",[this.last.x+=_20[i],this.last.y+=_20[i+1]]);
}
this.lastControl={};
},_hLineToA:function(_21,_22,_23){
for(var i=0;i<_23.length;++i){
_21.push("lineTo",[_23[i],this.last.y]);
}
this.last.x=_23[_23.length-1];
this.lastControl={};
},_hLineToR:function(_24,_25,_26){
for(var i=0;i<_26.length;++i){
_24.push("lineTo",[this.last.x+=_26[i],this.last.y]);
}
this.lastControl={};
},_vLineToA:function(_27,_28,_29){
for(var i=0;i<_29.length;++i){
_27.push("lineTo",[this.last.x,_29[i]]);
}
this.last.y=_29[_29.length-1];
this.lastControl={};
},_vLineToR:function(_2a,_2b,_2c){
for(var i=0;i<_2c.length;++i){
_2a.push("lineTo",[this.last.x,this.last.y+=_2c[i]]);
}
this.lastControl={};
},_curveToA:function(_2d,_2e,_2f){
for(var i=0;i<_2f.length;i+=6){
_2d.push("bezierCurveTo",_2f.slice(i,i+6));
}
this.last.x=_2f[_2f.length-2];
this.last.y=_2f[_2f.length-1];
this.lastControl.x=_2f[_2f.length-4];
this.lastControl.y=_2f[_2f.length-3];
this.lastControl.type="C";
},_curveToR:function(_30,_31,_32){
for(var i=0;i<_32.length;i+=6){
_30.push("bezierCurveTo",[this.last.x+_32[i],this.last.y+_32[i+1],this.lastControl.x=this.last.x+_32[i+2],this.lastControl.y=this.last.y+_32[i+3],this.last.x+_32[i+4],this.last.y+_32[i+5]]);
this.last.x+=_32[i+4];
this.last.y+=_32[i+5];
}
this.lastControl.type="C";
},_smoothCurveToA:function(_33,_34,_35){
for(var i=0;i<_35.length;i+=4){
var _36=this.lastControl.type=="C";
_33.push("bezierCurveTo",[_36?2*this.last.x-this.lastControl.x:this.last.x,_36?2*this.last.y-this.lastControl.y:this.last.y,_35[i],_35[i+1],_35[i+2],_35[i+3]]);
this.lastControl.x=_35[i];
this.lastControl.y=_35[i+1];
this.lastControl.type="C";
}
this.last.x=_35[_35.length-2];
this.last.y=_35[_35.length-1];
},_smoothCurveToR:function(_37,_38,_39){
for(var i=0;i<_39.length;i+=4){
var _3a=this.lastControl.type=="C";
_37.push("bezierCurveTo",[_3a?2*this.last.x-this.lastControl.x:this.last.x,_3a?2*this.last.y-this.lastControl.y:this.last.y,this.last.x+_39[i],this.last.y+_39[i+1],this.last.x+_39[i+2],this.last.y+_39[i+3]]);
this.lastControl.x=this.last.x+_39[i];
this.lastControl.y=this.last.y+_39[i+1];
this.lastControl.type="C";
this.last.x+=_39[i+2];
this.last.y+=_39[i+3];
}
},_qCurveToA:function(_3b,_3c,_3d){
for(var i=0;i<_3d.length;i+=4){
_3b.push("quadraticCurveTo",_3d.slice(i,i+4));
}
this.last.x=_3d[_3d.length-2];
this.last.y=_3d[_3d.length-1];
this.lastControl.x=_3d[_3d.length-4];
this.lastControl.y=_3d[_3d.length-3];
this.lastControl.type="Q";
},_qCurveToR:function(_3e,_3f,_40){
for(var i=0;i<_40.length;i+=4){
_3e.push("quadraticCurveTo",[this.lastControl.x=this.last.x+_40[i],this.lastControl.y=this.last.y+_40[i+1],this.last.x+_40[i+2],this.last.y+_40[i+3]]);
this.last.x+=_40[i+2];
this.last.y+=_40[i+3];
}
this.lastControl.type="Q";
},_qSmoothCurveToA:function(_41,_42,_43){
for(var i=0;i<_43.length;i+=2){
var _44=this.lastControl.type=="Q";
_41.push("quadraticCurveTo",[this.lastControl.x=_44?2*this.last.x-this.lastControl.x:this.last.x,this.lastControl.y=_44?2*this.last.y-this.lastControl.y:this.last.y,_43[i],_43[i+1]]);
this.lastControl.type="Q";
}
this.last.x=_43[_43.length-2];
this.last.y=_43[_43.length-1];
},_qSmoothCurveToR:function(_45,_46,_47){
for(var i=0;i<_47.length;i+=2){
var _48=this.lastControl.type=="Q";
_45.push("quadraticCurveTo",[this.lastControl.x=_48?2*this.last.x-this.lastControl.x:this.last.x,this.lastControl.y=_48?2*this.last.y-this.lastControl.y:this.last.y,this.last.x+_47[i],this.last.y+_47[i+1]]);
this.lastControl.type="Q";
this.last.x+=_47[i];
this.last.y+=_47[i+1];
}
},_arcTo:function(_49,_4a,_4b){
var _4c=_4a=="a";
for(var i=0;i<_4b.length;i+=7){
var x1=_4b[i+5],y1=_4b[i+6];
if(_4c){
x1+=this.last.x;
y1+=this.last.y;
}
var _4d=ga.arcAsBezier(this.last,_4b[i],_4b[i+1],_4b[i+2],_4b[i+3]?1:0,_4b[i+4]?1:0,x1,y1);
d.forEach(_4d,function(p){
_49.push("bezierCurveTo",p);
});
this.last.x=x1;
this.last.y=y1;
}
this.lastControl={};
},_closePath:function(_4e,_4f,_50){
_4e.push("closePath",[]);
this.lastControl={};
}});
d.forEach(["moveTo","lineTo","hLineTo","vLineTo","curveTo","smoothCurveTo","qCurveTo","qSmoothCurveTo","arcTo","closePath"],function(_51){
_a(g.Path,_51);
});
dojo.declare("dojox.gfx.TextPath",g.path.TextPath,{_renderShape:function(ctx){
var s=this.shape;
}});
dojo.declare("dojox.gfx.Surface",gs.Surface,{constructor:function(){
gs.Container._init.call(this);
this.pendingImageCount=0;
this.makeDirty();
},setDimensions:function(_52,_53){
this.width=g.normalizedLength(_52);
this.height=g.normalizedLength(_53);
if(!this.rawNode){
return this;
}
this.rawNode.width=_52;
this.rawNode.height=_53;
this.makeDirty();
return this;
},getDimensions:function(){
return this.rawNode?{width:this.rawNode.width,height:this.rawNode.height}:null;
},_render:function(){
if(this.pendingImageCount){
return;
}
var ctx=this.rawNode.getContext("2d");
ctx.save();
ctx.clearRect(0,0,this.rawNode.width,this.rawNode.height);
for(var i=0;i<this.children.length;++i){
this.children[i]._render(ctx);
}
ctx.restore();
if("pendingRender" in this){
clearTimeout(this.pendingRender);
delete this.pendingRender;
}
},makeDirty:function(){
if(!this.pendingImagesCount&&!("pendingRender" in this)){
this.pendingRender=setTimeout(d.hitch(this,this._render),0);
}
},downloadImage:function(img,url){
var _54=d.hitch(this,this.onImageLoad);
if(!this.pendingImageCount++&&"pendingRender" in this){
clearTimeout(this.pendingRender);
delete this.pendingRender;
}
img.onload=_54;
img.onerror=_54;
img.onabort=_54;
img.src=url;
},onImageLoad:function(){
if(!--this.pendingImageCount){
this._render();
}
},getEventSource:function(){
return null;
},connect:function(){
},disconnect:function(){
}});
g.createSurface=function(_55,_56,_57){
if(!_56&&!_57){
var pos=d.position(_55);
_56=_56||pos.w;
_57=_57||pos.h;
}
if(typeof _56=="number"){
_56=_56+"px";
}
if(typeof _57=="number"){
_57=_57+"px";
}
var s=new g.Surface(),p=d.byId(_55),c=p.ownerDocument.createElement("canvas");
c.width=dojox.gfx.normalizedLength(_56);
c.height=dojox.gfx.normalizedLength(_57);
p.appendChild(c);
s.rawNode=c;
s._parent=p;
s.surface=s;
return s;
};
var C=gs.Container,_58={add:function(_59){
this.surface.makeDirty();
return C.add.apply(this,arguments);
},remove:function(_5a,_5b){
this.surface.makeDirty();
return C.remove.apply(this,arguments);
},clear:function(){
this.surface.makeDirty();
return C.clear.apply(this,arguments);
},_moveChildToFront:function(_5c){
this.surface.makeDirty();
return C._moveChildToFront.apply(this,arguments);
},_moveChildToBack:function(_5d){
this.surface.makeDirty();
return C._moveChildToBack.apply(this,arguments);
}};
d.mixin(gs.Creator,{createObject:function(_5e,_5f){
var _60=new _5e();
_60.surface=this.surface;
_60.setShape(_5f);
this.add(_60);
return _60;
}});
d.extend(g.Group,_58);
d.extend(g.Group,gs.Creator);
d.extend(g.Surface,_58);
d.extend(g.Surface,gs.Creator);
})();
}
