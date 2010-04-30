/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dojox.charting.plot2d.Bubble"]){
dojo._hasResource["dojox.charting.plot2d.Bubble"]=true;
dojo.provide("dojox.charting.plot2d.Bubble");
dojo.require("dojox.charting.plot2d.Base");
dojo.require("dojox.lang.functional");
(function(){
var df=dojox.lang.functional,du=dojox.lang.utils,dc=dojox.charting.plot2d.common,_1=df.lambda("item.purgeGroup()");
dojo.declare("dojox.charting.plot2d.Bubble",dojox.charting.plot2d.Base,{defaultParams:{hAxis:"x",vAxis:"y"},optionalParams:{},constructor:function(_2,_3){
this.opt=dojo.clone(this.defaultParams);
du.updateWithObject(this.opt,_3);
this.series=[];
this.hAxis=this.opt.hAxis;
this.vAxis=this.opt.vAxis;
},calculateAxes:function(_4){
this._calc(_4,dc.collectSimpleStats(this.series));
return this;
},render:function(_5,_6){
this.dirty=this.isDirty();
if(this.dirty){
dojo.forEach(this.series,_1);
this.cleanGroup();
var s=this.group;
df.forEachRev(this.series,function(_7){
_7.cleanGroup(s);
});
}
var t=this.chart.theme,_8,_9,_a,_b,_c,ht=this._hScaler.scaler.getTransformerFromModel(this._hScaler),vt=this._vScaler.scaler.getTransformerFromModel(this._vScaler),_d=this.events();
this.resetEvents();
for(var i=this.series.length-1;i>=0;--i){
var _e=this.series[i];
if(!this.dirty&&!_e.dirty){
continue;
}
_e.cleanGroup();
if(!_e.data.length){
_e.dirty=false;
continue;
}
if(typeof _e.data[0]=="number"){
console.warn("dojox.charting.plot2d.Bubble: the data in the following series cannot be rendered as a bubble chart; ",_e);
continue;
}
var s=_e.group,_f=dojo.map(_e.data,function(v,i){
return {x:ht(v.x)+_6.l,y:_5.height-_6.b-vt(v.y),radius:this._vScaler.bounds.scale*(v.size/2)};
},this);
if(_e.fill){
_a=_e.fill;
}else{
if(_e.stroke){
_a=_e.stroke;
}else{
_a=_e.dyn.color=new dojo.Color(t.next("color"));
}
}
_e.dyn.fill=_a;
_8=_e.dyn.stroke=_e.stroke?dc.makeStroke(_e.stroke):dc.augmentStroke(t.series.stroke,_a);
var _10=null,_11=null,_12=null;
if(this.opt.shadows&&_8){
var sh=this.opt.shadows,_c=new dojo.Color([0,0,0,0.2]),_b=dojo.clone(_9?_9:_8);
_b.color=_c;
_b.width+=sh.dw?sh.dw:0;
_e.dyn.shadow=_b;
var _13=dojo.map(_f,function(_14){
var sh=this.opt.shadows;
return s.createCircle({cx:_14.x+sh.dx,cy:_14.y+sh.dy,r:_14.radius}).setStroke(_b).setFill(_c);
},this);
}
if(_e.outline||t.series.outline){
_9=dc.makeStroke(_e.outline?_e.outline:t.series.outline);
_9.width=2*_9.width+_8.width;
_e.dyn.outline=_9;
_11=dojo.map(_f,function(_15){
s.createCircle({cx:_15.x,cy:_15.y,r:_15.radius}).setStroke(_9);
},this);
}
_10=dojo.map(_f,function(_16){
return s.createCircle({cx:_16.x,cy:_16.y,r:_16.radius}).setStroke(_8).setFill(_a);
},this);
if(_d){
dojo.forEach(_10,function(s,i){
var o={element:"circle",index:i,run:_e,plot:this,hAxis:this.hAxis||null,vAxis:this.vAxis||null,shape:s,outline:_11&&_11[i]||null,shadow:_12&&_12[i]||null,x:_e.data[i].x,y:_e.data[i].y,r:_e.data[i].size/2,cx:_f[i].x,cy:_f[i].y,cr:_f[i].radius};
this._connectEvents(s,o);
},this);
}
_e.dirty=false;
}
this.dirty=false;
return this;
}});
})();
}
