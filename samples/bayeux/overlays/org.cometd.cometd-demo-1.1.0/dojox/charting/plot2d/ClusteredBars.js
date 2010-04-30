/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dojox.charting.plot2d.ClusteredBars"]){
dojo._hasResource["dojox.charting.plot2d.ClusteredBars"]=true;
dojo.provide("dojox.charting.plot2d.ClusteredBars");
dojo.require("dojox.charting.plot2d.common");
dojo.require("dojox.charting.plot2d.Bars");
dojo.require("dojox.lang.functional");
dojo.require("dojox.lang.functional.reversed");
(function(){
var df=dojox.lang.functional,dc=dojox.charting.plot2d.common,_1=df.lambda("item.purgeGroup()");
dojo.declare("dojox.charting.plot2d.ClusteredBars",dojox.charting.plot2d.Bars,{render:function(_2,_3){
this.dirty=this.isDirty();
if(this.dirty){
dojo.forEach(this.series,_1);
this.cleanGroup();
var s=this.group;
df.forEachRev(this.series,function(_4){
_4.cleanGroup(s);
});
}
var t=this.chart.theme,_5,_6,_7,f,_8,_9,_a,ht=this._hScaler.scaler.getTransformerFromModel(this._hScaler),vt=this._vScaler.scaler.getTransformerFromModel(this._vScaler),_b=Math.max(0,this._hScaler.bounds.lower),_c=ht(_b),_d=this.events();
f=dc.calculateBarSize(this._vScaler.bounds.scale,this.opt,this.series.length);
_8=f.gap;
_9=_a=f.size;
this.resetEvents();
for(var i=this.series.length-1;i>=0;--i){
var _e=this.series[i],_f=_a*(this.series.length-i-1);
if(!this.dirty&&!_e.dirty){
continue;
}
_e.cleanGroup();
var s=_e.group;
if(!_e.fill||!_e.stroke){
_5=_e.dyn.color=new dojo.Color(t.next("color"));
}
_6=_e.stroke?_e.stroke:dc.augmentStroke(t.series.stroke,_5);
_7=_e.fill?_e.fill:dc.augmentFill(t.series.fill,_5);
for(var j=0;j<_e.data.length;++j){
var _10=_e.data[j],v=typeof _10=="number"?_10:_10.y,hv=ht(v),_11=hv-_c,w=Math.abs(_11),_12=_5,_13=_7,_14=_6;
if(typeof _10!="number"){
if(_10.color){
_12=new dojo.Color(_10.color);
}
if("fill" in _10){
_13=_10.fill;
}else{
if(_10.color){
_13=dc.augmentFill(t.series.fill,_12);
}
}
if("stroke" in _10){
_14=_10.stroke;
}else{
if(_10.color){
_14=dc.augmentStroke(t.series.stroke,_12);
}
}
}
if(w>=1&&_9>=1){
var _15=s.createRect({x:_3.l+(v<_b?hv:_c),y:_2.height-_3.b-vt(j+1.5)+_8+_f,width:w,height:_9}).setFill(_13).setStroke(_14);
_e.dyn.fill=_15.getFill();
_e.dyn.stroke=_15.getStroke();
if(_d){
var o={element:"bar",index:j,run:_e,plot:this,hAxis:this.hAxis||null,vAxis:this.vAxis||null,shape:_15,x:v,y:j+1.5};
this._connectEvents(_15,o);
}
if(this.animate){
this._animateBar(_15,_3.l+_c,-_11);
}
}
}
_e.dirty=false;
}
this.dirty=false;
return this;
}});
})();
}
