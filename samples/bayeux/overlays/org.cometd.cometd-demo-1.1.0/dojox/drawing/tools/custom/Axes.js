/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dojox.drawing.tools.custom.Axes"]){
dojo._hasResource["dojox.drawing.tools.custom.Axes"]=true;
dojo.provide("dojox.drawing.tools.custom.Axes");
dojo.require("dojox.drawing.stencil.Path");
dojox.drawing.tools.custom.Axes=dojox.drawing.util.oo.declare(dojox.drawing.stencil.Path,function(_1){
this.closePath=false;
this.xArrow=new dojox.drawing.annotations.Arrow({stencil:this,idx1:0,idx2:1});
this.yArrow=new dojox.drawing.annotations.Arrow({stencil:this,idx1:2,idx2:1});
if(this.points&&this.points.length){
this.setPoints=this._postSetPoints;
this.render();
this.onRender();
}
},{draws:true,type:"dojox.drawing.tools.custom.Axes",minimumSize:30,showAngle:true,closePath:false,baseRender:false,createLabels:function(){
var _2={align:"middle",valign:"middle",util:this.util,annotation:true,container:this.container,mouse:this.mouse,stencil:this};
this.labelX=new dojox.drawing.annotations.Label(dojo.mixin(_2,{labelPosition:this.setLabelX}));
this.labelY=new dojox.drawing.annotations.Label(dojo.mixin(_2,{labelPosition:this.setLabelY}));
},setLabelX:function(){
var ax=this.points[0];
var c=this.points[1];
var ay=this.points[2];
var _3=40;
var _4=20;
var pt,px,py,_5;
pt=this.util.lineSub(c.x,c.y,ax.x,ax.y,_3);
px=pt.x+(pt.y-ax.y);
py=pt.y+(ax.x-pt.x);
_5=this.util.lineSub(pt.x,pt.y,px,py,(_3-_4));
return {x:_5.x,y:_5.y,width:20};
},setLabelY:function(){
var ax=this.points[0];
var c=this.points[1];
var ay=this.points[2];
var _6=40;
var _7=20;
var pt,px,py,_8;
pt=this.util.lineSub(c.x,c.y,ay.x,ay.y,_6);
px=pt.x+(ay.y-pt.y);
py=pt.y+(pt.x-ay.x);
_8=this.util.lineSub(pt.x,pt.y,px,py,(_6-_7));
_8=this.util.lineSub(pt.x,pt.y,px,py,(_6-_7));
return {x:_8.x,y:_8.y,width:20};
},setLabel:function(_9){
if(this._labelsCreated){
return;
}
!this.labelX&&this.createLabels();
var x="x";
var y="y";
if(_9){
_9.replace(/^\s+/,"");
_9.replace(/\s+$/,"");
var _a=_9.match(/(.*?)(and|&)(.*)/i);
if(_a.length>2){
x=_a[1].replace(/\s+$/,"");
y=_a[3].replace(/^\s+/,"");
}
}
this.labelX.setLabel(x);
this.labelY.setLabel(y);
this._labelsCreated=true;
},getLabel:function(){
if(!this.labelX){
return {};
}
return {x:this.labelX._text,y:this.labelY._text};
},anchorPositionCheck:function(x,y,_b){
var pm=this.container.getParent().getTransform();
var am=_b.shape.getTransform();
var p=this.points;
var o={x:am.dx+_b.org.x+pm.dx,y:am.dy+_b.org.y+pm.dy};
var c={x:p[1].x+pm.dx,y:p[1].y+pm.dy};
var ox=c.x-(c.y-o.y);
var oy=c.y-(o.x-c.x);
return {x:ox,y:oy};
},onTransformBegin:function(_c){
this._isBeingModified=true;
},onTransformEnd:function(_d){
if(!_d){
return;
}
this._isBeingModified=false;
this._toggleSelected();
var o=this.points[0];
var c=this.points[1];
var pt=this.util.constrainAngle({start:{x:c.x,y:c.y},x:o.x,y:o.y},91,180);
if(pt.x==o.x&&pt.y==o.y){
var _e={start:{x:c.x,y:c.y},x:o.x,y:o.y};
pt=this.util.snapAngle(_e,this.angleSnap/180);
_e.x=pt.x;
_e.y=pt.y;
var ox=_e.start.x-(_e.start.y-_e.y);
var oy=_e.start.y-(_e.x-_e.start.x);
if(ox<0||oy<0){
console.warn("AXES ERROR LESS THAN ZERO - ABORT");
return;
}
this.points=[{x:_e.x,y:_e.y},{x:_e.start.x,y:_e.start.y,noAnchor:true}];
this.points.push({x:ox,y:oy,noAnchor:true});
this.setPoints(this.points);
this.onModify(this);
return;
}
this.points[0].x=pt.x;
this.points[0].y=pt.y;
o=this.points[0];
var ox=c.x-(c.y-o.y);
var oy=c.y-(o.x-c.x);
this.points[2]={x:ox,y:oy,noAnchor:true};
this.setPoints(this.points);
this.labelX.setLabel();
this.labelY.setLabel();
this.onModify(this);
},getBounds:function(_f){
var px=this.points[0],pc=this.points[1],py=this.points[2];
if(_f){
return {x:pc.x,y:pc.y,x1:pc.x,y1:pc.y,x2:px.x,y2:px.y,x3:py.x,y3:py.y};
}
var x1=py.x,y1=py.y<px.y?py.y:px.y,x2=px.x,y2=pc.y;
return {x1:x1,y1:y1,x2:x2,y2:y2,x:x1,y:y1,w:x2-x1,h:y2-y1};
},_postSetPoints:function(pts){
this.points[0]=pts[0];
if(this.pointsToData){
this.data=this.pointsToData();
}
},onTransform:function(_10){
var o=this.points[0];
var c=this.points[1];
var ox=c.x-(c.y-o.y);
var oy=c.y-(o.x-c.x);
this.points[2]={x:ox,y:oy,noAnchor:true};
this.setPoints(this.points);
if(!this._isBeingModified){
this.onTransformBegin();
}
this.render();
},pointsToData:function(){
var p=this.points;
return {x1:p[1].x,y1:p[1].y,x2:p[0].x,y2:p[0].y,x3:p[2].x,y3:p[2].y};
},dataToPoints:function(o){
o=o||this.data;
if(o.radius||o.angle){
o.angle=(180-o.angle)<0?180-o.angle+360:180-o.angle;
var pt=this.util.pointOnCircle(o.x,o.y,o.radius,o.angle);
var ox=o.x-(o.y-pt.y);
var oy=o.y-(pt.x-o.x);
this.data=o={x2:o.x,y2:o.y,x1:pt.x,y1:pt.y,x3:ox,y3:oy};
}
this.points=[{x:o.x1,y:o.y1},{x:o.x2,y:o.y2,noAnchor:true},{x:o.x3,y:o.y3,noAnchor:true}];
return this.points;
},onDrag:function(obj){
var pt=this.util.constrainAngle(obj,91,180);
obj.x=pt.x;
obj.y=pt.y;
var ox=obj.start.x-(obj.start.y-obj.y);
var oy=obj.start.y-(obj.x-obj.start.x);
if(ox<0||oy<0){
return;
}
this.points=[{x:obj.x,y:obj.y},{x:obj.start.x,y:obj.start.y,noAnchor:true}];
this.points.push({x:ox,y:oy,noAnchor:true});
this.render();
},onUp:function(obj){
var p=this.points;
if(!p.length){
return;
}
var len=this.util.distance(p[1].x,p[1].y,p[0].x,p[0].y);
if(!p||!p.length){
return;
}else{
if(len<this.minimumSize){
this.remove(this.shape,this.hit);
this.xArrow.remove(this.xArrow.shape,this.xArrow.hit);
this.yArrow.remove(this.yArrow.shape,this.yArrow.hit);
return;
}
}
var o=p[0];
var c=p[1];
obj={start:{x:c.x,y:c.y},x:o.x,y:o.y};
var pt=this.util.snapAngle(obj,this.angleSnap/180);
obj.x=pt.x;
obj.y=pt.y;
var ox=obj.start.x-(obj.start.y-obj.y);
var oy=obj.start.y-(obj.x-obj.start.x);
if(ox<0||oy<0){
return;
}
this.points=[{x:obj.x,y:obj.y},{x:obj.start.x,y:obj.start.y,noAnchor:true}];
this.points.push({x:ox,y:oy,noAnchor:true});
this.onRender(this);
this.setPoints=this._postSetPoints;
}});
dojox.drawing.tools.custom.Axes.setup={name:"dojox.drawing.tools.custom.Axes",tooltip:"Axes Tool",iconClass:"iconAxes"};
dojox.drawing.register(dojox.drawing.tools.custom.Axes.setup,"tool");
}
