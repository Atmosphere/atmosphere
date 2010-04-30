/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dojox.drawing.tools.Arrow"]){
dojo._hasResource["dojox.drawing.tools.Arrow"]=true;
dojo.provide("dojox.drawing.tools.Arrow");
dojox.drawing.tools.Arrow=dojox.drawing.util.oo.declare(dojox.drawing.tools.Line,function(_1){
if(this.arrowStart){
this.begArrow=new dojox.drawing.annotations.Arrow({stencil:this,idx1:0,idx2:1});
}
if(this.arrowEnd){
this.endArrow=new dojox.drawing.annotations.Arrow({stencil:this,idx1:1,idx2:0});
}
if(this.points.length){
this.render();
}
},{draws:true,type:"dojox.drawing.tools.Arrow",baseRender:false,arrowStart:false,arrowEnd:true,onUp:function(_2){
if(this.created||!this.shape){
return;
}
var p=this.points;
var _3=this.util.distance(p[0].x,p[0].y,p[1].x,p[1].y);
if(_3<this.minimumSize){
this.remove(this.shape,this.hit);
return;
}
var pt=this.util.snapAngle(_2,this.angleSnap/180);
this.setPoints([{x:p[0].x,y:p[0].y},{x:pt.x,y:pt.y}]);
this.renderedOnce=true;
this.onRender(this);
}});
dojox.drawing.tools.Arrow.setup={name:"dojox.drawing.tools.Arrow",tooltip:"Arrow Tool",iconClass:"iconArrow"};
dojox.drawing.register(dojox.drawing.tools.Arrow.setup,"tool");
}
