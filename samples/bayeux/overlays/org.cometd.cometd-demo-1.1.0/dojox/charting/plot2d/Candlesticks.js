/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dojox.charting.plot2d.Candlesticks"]){
dojo._hasResource["dojox.charting.plot2d.Candlesticks"]=true;
dojo.provide("dojox.charting.plot2d.Candlesticks");
dojo.require("dojox.charting.plot2d.common");
dojo.require("dojox.charting.plot2d.Base");
dojo.require("dojox.lang.utils");
dojo.require("dojox.lang.functional");
dojo.require("dojox.lang.functional.reversed");
(function(){
var df=dojox.lang.functional,du=dojox.lang.utils,dc=dojox.charting.plot2d.common,_1=df.lambda("item.purgeGroup()");
dojo.declare("dojox.charting.plot2d.Candlesticks",dojox.charting.plot2d.Base,{defaultParams:{hAxis:"x",vAxis:"y",gap:2,shadows:null},optionalParams:{minBarSize:1,maxBarSize:1},constructor:function(_2,_3){
this.opt=dojo.clone(this.defaultParams);
du.updateWithObject(this.opt,_3);
du.updateWithPattern(this.opt,_3,this.optionalParams);
this.series=[];
this.hAxis=this.opt.hAxis;
this.vAxis=this.opt.vAxis;
},collectStats:function(_4){
var _5=dojo.clone(dc.defaultStats);
for(var i=0;i<_4.length;i++){
var _6=_4[i];
if(!_6.data.length){
continue;
}
var _7=_5.vmin,_8=_5.vmax;
if(!("ymin" in _6)||!("ymax" in _6)){
dojo.forEach(_6.data,function(_9,_a){
var x=_9.x||_a+1;
_5.hmin=Math.min(_5.hmin,x);
_5.hmax=Math.max(_5.hmax,x);
_5.vmin=Math.min(_5.vmin,_9.open,_9.close,_9.high,_9.low);
_5.vmax=Math.max(_5.vmax,_9.open,_9.close,_9.high,_9.low);
});
}
if("ymin" in _6){
_5.vmin=Math.min(_7,_6.ymin);
}
if("ymax" in _6){
_5.vmax=Math.max(_8,_6.ymax);
}
}
return _5;
},calculateAxes:function(_b){
var _c=this.collectStats(this.series),t;
_c.hmin-=0.5;
_c.hmax+=0.5;
this._calc(_b,_c);
return this;
},render:function(_d,_e){
this.dirty=this.isDirty();
if(this.dirty){
dojo.forEach(this.series,_1);
this.cleanGroup();
var s=this.group;
df.forEachRev(this.series,function(_f){
_f.cleanGroup(s);
});
}
var t=this.chart.theme,_10,_11,_12,f,gap,_13,ht=this._hScaler.scaler.getTransformerFromModel(this._hScaler),vt=this._vScaler.scaler.getTransformerFromModel(this._vScaler),_14=Math.max(0,this._vScaler.bounds.lower),_15=vt(_14),_16=this.events();
f=dc.calculateBarSize(this._hScaler.bounds.scale,this.opt);
gap=f.gap;
_13=f.size;
this.resetEvents();
for(var i=this.series.length-1;i>=0;--i){
var run=this.series[i];
if(!this.dirty&&!run.dirty){
continue;
}
run.cleanGroup();
var s=run.group;
if(!run.fill||!run.stroke){
_10=run.dyn.color=new dojo.Color(t.next("color"));
}
_11=run.stroke?run.stroke:dc.augmentStroke(t.series.stroke,_10);
_12=run.fill?run.fill:dc.augmentFill(t.series.fill,_10);
for(var j=0;j<run.data.length;++j){
var v=run.data[j];
var x=ht(v.x||(j+0.5))+_e.l+gap,y=_d.height-_e.b,_17=vt(v.open),_18=vt(v.close),_19=vt(v.high),low=vt(v.low);
if("mid" in v){
var mid=vt(v.mid);
}
if(low>_19){
var tmp=_19;
_19=low;
low=tmp;
}
if(_13>=1){
var _1a=_17>_18;
var _1b={x1:_13/2,x2:_13/2,y1:y-_19,y2:y-low},_1c={x:0,y:y-Math.max(_17,_18),width:_13,height:Math.max(_1a?_17-_18:_18-_17,1)};
shape=s.createGroup();
shape.setTransform({dx:x,dy:0});
var _1d=shape.createGroup();
_1d.createLine(_1b).setStroke(_11);
_1d.createRect(_1c).setStroke(_11).setFill(_1a?_12:"white");
if("mid" in v){
_1d.createLine({x1:(_11.width||1),x2:_13-(_11.width||1),y1:y-mid,y2:y-mid}).setStroke(_1a?{color:"white"}:_11);
}
run.dyn.fill=_12;
run.dyn.stroke=_11;
if(_16){
var o={element:"candlestick",index:j,run:run,plot:this,hAxis:this.hAxis||null,vAxis:this.vAxis||null,shape:_1d,x:x,y:y-Math.max(_17,_18),cx:_13/2,cy:(y-Math.max(_17,_18))+(Math.max(_1a?_17-_18:_18-_17,1)/2),width:_13,height:Math.max(_1a?_17-_18:_18-_17,1),data:v};
this._connectEvents(shape,o);
}
}
}
run.dirty=false;
}
this.dirty=false;
return this;
}});
})();
}
