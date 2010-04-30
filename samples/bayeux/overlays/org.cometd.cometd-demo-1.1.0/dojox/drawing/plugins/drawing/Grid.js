/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dojox.drawing.plugins.drawing.Grid"]){
dojo._hasResource["dojox.drawing.plugins.drawing.Grid"]=true;
dojo.provide("dojox.drawing.plugins.drawing.Grid");
dojo.require("dojox.drawing.plugins._Plugin");
dojox.drawing.plugins.drawing.Grid=dojox.drawing.util.oo.declare(dojox.drawing.plugins._Plugin,function(_1){
if(_1.gap){
this.major=_1.gap;
}
this.setGrid();
dojo.connect(this.canvas,"setZoom",this,"setZoom");
},{type:"dojox.drawing.plugins.drawing.Grid",gap:100,major:100,minor:0,zoom:1,setZoom:function(_2){
this.zoom=_2;
this.setGrid();
},setGrid:function(_3){
var _4=Math.floor(this.major*this.zoom);
var _5=this.minor?Math.floor(this.minor*this.zoom):_4;
this.grid&&this.grid.removeShape();
var x1,x2,y1,y2,i,_6;
var s=this.canvas.underlay.createGroup();
var w=2000;
var h=1000;
var b=1;
var mj="#00ffff";
var mn="#d7ffff";
var _7=function(x1,y1,x2,y2,c){
s.createLine({x1:x1,y1:y1,x2:x2,y2:y2}).setStroke({style:"Solid",width:b,cap:"round",color:c});
};
for(i=1,len=h/_5;i<len;i++){
x1=0,x2=w;
y1=_5*i,y2=y1;
_6=y1%_4?mn:mj;
_7(x1,y1,x2,y2,_6);
}
for(i=1,len=w/_5;i<len;i++){
y1=0,y2=h;
x1=_5*i,x2=x1;
_6=x1%_4?mn:mj;
_7(x1,y1,x2,y2,_6);
}
s.moveToBack();
this.grid=s;
this.util.attr(s,"id","grid");
return s;
}});
}
