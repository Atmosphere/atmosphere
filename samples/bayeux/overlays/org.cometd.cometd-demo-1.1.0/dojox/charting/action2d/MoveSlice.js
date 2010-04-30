/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dojox.charting.action2d.MoveSlice"]){
dojo._hasResource["dojox.charting.action2d.MoveSlice"]=true;
dojo.provide("dojox.charting.action2d.MoveSlice");
dojo.require("dojox.charting.action2d.Base");
dojo.require("dojox.gfx.matrix");
dojo.require("dojox.lang.functional");
dojo.require("dojox.lang.functional.scan");
dojo.require("dojox.lang.functional.fold");
(function(){
var _1=1.05,_2=7,m=dojox.gfx.matrix,gf=dojox.gfx.fx,df=dojox.lang.functional;
dojo.declare("dojox.charting.action2d.MoveSlice",dojox.charting.action2d.Base,{defaultParams:{duration:400,easing:dojo.fx.easing.backOut,scale:_1,shift:_2},optionalParams:{},constructor:function(_3,_4,_5){
if(!_5){
_5={};
}
this.scale=typeof _5.scale=="number"?_5.scale:_1;
this.shift=typeof _5.shift=="number"?_5.shift:_2;
this.connect();
},process:function(o){
if(!o.shape||o.element!="slice"||!(o.type in this.overOutEvents)){
return;
}
if(!this.angles){
if(typeof o.run.data[0]=="number"){
this.angles=df.map(df.scanl(o.run.data,"+",0),"* 2 * Math.PI / this",df.foldl(o.run.data,"+",0));
}else{
this.angles=df.map(df.scanl(o.run.data,"a + b.y",0),"* 2 * Math.PI / this",df.foldl(o.run.data,"a + b.y",0));
}
}
var _6=o.index,_7,_8,_9,_a,_b,_c=(this.angles[_6]+this.angles[_6+1])/2,_d=m.rotateAt(-_c,o.cx,o.cy),_e=m.rotateAt(_c,o.cx,o.cy);
_7=this.anim[_6];
if(_7){
_7.action.stop(true);
}else{
this.anim[_6]=_7={};
}
if(o.type=="onmouseover"){
_a=0;
_b=this.shift;
_8=1;
_9=this.scale;
}else{
_a=this.shift;
_b=0;
_8=this.scale;
_9=1;
}
_7.action=dojox.gfx.fx.animateTransform({shape:o.shape,duration:this.duration,easing:this.easing,transform:[_e,{name:"translate",start:[_a,0],end:[_b,0]},{name:"scaleAt",start:[_8,o.cx,o.cy],end:[_9,o.cx,o.cy]},_d]});
if(o.type=="onmouseout"){
dojo.connect(_7.action,"onEnd",this,function(){
delete this.anim[_6];
});
}
_7.action.play();
},reset:function(){
delete this.angles;
}});
})();
}
