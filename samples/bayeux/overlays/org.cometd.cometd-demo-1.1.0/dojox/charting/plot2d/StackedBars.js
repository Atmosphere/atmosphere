/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dojox.charting.plot2d.StackedBars"]){
dojo._hasResource["dojox.charting.plot2d.StackedBars"]=true;
dojo.provide("dojox.charting.plot2d.StackedBars");
dojo.require("dojox.charting.plot2d.common");
dojo.require("dojox.charting.plot2d.Bars");
dojo.require("dojox.lang.functional");
dojo.require("dojox.lang.functional.reversed");
(function(){
var df=dojox.lang.functional,dc=dojox.charting.plot2d.common,_1=df.lambda("item.purgeGroup()");
dojo.declare("dojox.charting.plot2d.StackedBars",dojox.charting.plot2d.Bars,{calculateAxes:function(_2){
var _3=dc.collectStackedStats(this.series),t;
this._maxRunLength=_3.hmax;
_3.hmin-=0.5;
_3.hmax+=0.5;
t=_3.hmin,_3.hmin=_3.vmin,_3.vmin=t;
t=_3.hmax,_3.hmax=_3.vmax,_3.vmax=t;
this._calc(_2,_3);
return this;
},render:function(_4,_5){
if(this._maxRunLength<=0){
return this;
}
var _6=df.repeat(this._maxRunLength,"-> 0",0);
for(var i=0;i<this.series.length;++i){
var _7=this.series[i];
for(var j=0;j<_7.data.length;++j){
var _8=_7.data[j],v=typeof _8=="number"?_8:_8.y;
if(isNaN(v)){
v=0;
}
_6[j]+=v;
}
}
this.dirty=this.isDirty();
if(this.dirty){
dojo.forEach(this.series,_1);
this.cleanGroup();
var s=this.group;
df.forEachRev(this.series,function(_9){
_9.cleanGroup(s);
});
}
var t=this.chart.theme,_a,_b,_c,f,_d,_e,ht=this._hScaler.scaler.getTransformerFromModel(this._hScaler),vt=this._vScaler.scaler.getTransformerFromModel(this._vScaler),_f=this.events();
f=dc.calculateBarSize(this._vScaler.bounds.scale,this.opt);
_d=f.gap;
_e=f.size;
this.resetEvents();
for(var i=this.series.length-1;i>=0;--i){
var _7=this.series[i];
if(!this.dirty&&!_7.dirty){
continue;
}
_7.cleanGroup();
var s=_7.group;
if(!_7.fill||!_7.stroke){
_a=_7.dyn.color=new dojo.Color(t.next("color"));
}
_b=_7.stroke?_7.stroke:dc.augmentStroke(t.series.stroke,_a);
_c=_7.fill?_7.fill:dc.augmentFill(t.series.fill,_a);
for(var j=0;j<_6.length;++j){
var v=_6[j],_10=ht(v),_8=_7.data[j],_11=_a,_12=_c,_13=_b;
if(typeof _8!="number"){
if(_8.color){
_11=new dojo.Color(_8.color);
}
if("fill" in _8){
_12=_8.fill;
}else{
if(_8.color){
_12=dc.augmentFill(t.series.fill,_11);
}
}
if("stroke" in _8){
_13=_8.stroke;
}else{
if(_8.color){
_13=dc.augmentStroke(t.series.stroke,_11);
}
}
}
if(_10>=1&&_e>=1){
var _14=s.createRect({x:_5.l,y:_4.height-_5.b-vt(j+1.5)+_d,width:_10,height:_e}).setFill(_12).setStroke(_13);
_7.dyn.fill=_14.getFill();
_7.dyn.stroke=_14.getStroke();
if(_f){
var o={element:"bar",index:j,run:_7,plot:this,hAxis:this.hAxis||null,vAxis:this.vAxis||null,shape:_14,x:v,y:j+1.5};
this._connectEvents(_14,o);
}
if(this.animate){
this._animateBar(_14,_5.l,-_10);
}
}
}
_7.dirty=false;
for(var j=0;j<_7.data.length;++j){
var _8=_7.data[j],v=typeof _8=="number"?_8:_8.y;
if(isNaN(v)){
v=0;
}
_6[j]-=v;
}
}
this.dirty=false;
return this;
}});
})();
}
