/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dojox.drawing.tools.custom.Vector"]){
dojo._hasResource["dojox.drawing.tools.custom.Vector"]=true;
dojo.provide("dojox.drawing.tools.custom.Vector");
dojo.require("dojox.drawing.tools.Arrow");
dojo.require("dojox.drawing.util.positioning");
dojox.drawing.tools.custom.Vector=dojox.drawing.util.oo.declare(dojox.drawing.tools.Arrow,function(_1){
this.minimumSize=this.style.arrows.length;
},{draws:true,type:"dojox.drawing.tools.custom.Vector",minimumSize:30,showAngle:true,labelPosition:function(){
var d=this.data;
var pt=dojox.drawing.util.positioning.label({x:d.x1,y:d.y1},{x:d.x2,y:d.y2});
return {x:pt.x,y:pt.y};
},_createZeroVector:function(_2,d,_3){
var s=_2=="hit"?this.minimumSize:this.minimumSize/2;
var f=_2=="hit"?_3.fill:null;
d={cx:this.data.x1,cy:this.data.y1,rx:s,ry:s};
this.remove(this[_2]);
this[_2]=this.container.createEllipse(d).setStroke(_3).setFill(f);
this.util.attr(this[_2],"drawingType","stencil");
},render:function(){
this.onBeforeRender(this);
if(this.getRadius()>=this.minimumSize){
this._create("hit",this.data,this.style.currentHit);
this._create("shape",this.data,this.style.current);
}else{
this._createZeroVector("hit",this.data,this.style.currentHit);
this._createZeroVector("shape",this.data,this.style.current);
}
},onUp:function(_4){
if(this.created||!this.shape){
return;
}
if(this.getRadius()<this.minimumSize){
var p=this.points;
this.setPoints([{x:p[0].x,y:p[0].y},{x:p[0].x,y:p[0].y}]);
}else{
var pt=this.util.snapAngle(_4,this.angleSnap/180);
var p=this.points;
this.setPoints([{x:p[0].x,y:p[0].y},{x:pt.x,y:pt.y}]);
}
this.renderedOnce=true;
this.onRender(this);
}});
dojox.drawing.tools.custom.Vector.setup={name:"dojox.drawing.tools.custom.Vector",tooltip:"Vector Tool",iconClass:"iconVector"};
dojox.drawing.register(dojox.drawing.tools.custom.Vector.setup,"tool");
}
