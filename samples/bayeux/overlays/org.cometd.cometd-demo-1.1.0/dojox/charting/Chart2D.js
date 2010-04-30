/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dojox.charting.Chart2D"]){
dojo._hasResource["dojox.charting.Chart2D"]=true;
dojo.provide("dojox.charting.Chart2D");
dojo.require("dojox.gfx");
dojo.require("dojox.lang.functional");
dojo.require("dojox.lang.functional.fold");
dojo.require("dojox.lang.functional.reversed");
dojo.require("dojox.charting.Theme");
dojo.require("dojox.charting.Series");
dojo.require("dojox.charting.axis2d.Default");
dojo.require("dojox.charting.plot2d.Default");
dojo.require("dojox.charting.plot2d.Lines");
dojo.require("dojox.charting.plot2d.Areas");
dojo.require("dojox.charting.plot2d.Markers");
dojo.require("dojox.charting.plot2d.MarkersOnly");
dojo.require("dojox.charting.plot2d.Scatter");
dojo.require("dojox.charting.plot2d.Stacked");
dojo.require("dojox.charting.plot2d.StackedLines");
dojo.require("dojox.charting.plot2d.StackedAreas");
dojo.require("dojox.charting.plot2d.Columns");
dojo.require("dojox.charting.plot2d.StackedColumns");
dojo.require("dojox.charting.plot2d.ClusteredColumns");
dojo.require("dojox.charting.plot2d.Bars");
dojo.require("dojox.charting.plot2d.StackedBars");
dojo.require("dojox.charting.plot2d.ClusteredBars");
dojo.require("dojox.charting.plot2d.Grid");
dojo.require("dojox.charting.plot2d.Pie");
dojo.require("dojox.charting.plot2d.Bubble");
dojo.require("dojox.charting.plot2d.Candlesticks");
dojo.require("dojox.charting.plot2d.OHLC");
(function(){
var df=dojox.lang.functional,dc=dojox.charting,_1=df.lambda("item.clear()"),_2=df.lambda("item.purgeGroup()"),_3=df.lambda("item.destroy()"),_4=df.lambda("item.dirty = false"),_5=df.lambda("item.dirty = true");
dojo.declare("dojox.charting.Chart2D",null,{constructor:function(_6,_7){
if(!_7){
_7={};
}
this.margins=_7.margins?_7.margins:{l:10,t:10,r:10,b:10};
this.stroke=_7.stroke;
this.fill=_7.fill;
this.theme=null;
this.axes={};
this.stack=[];
this.plots={};
this.series=[];
this.runs={};
this.dirty=true;
this.coords=null;
this.node=dojo.byId(_6);
var _8=dojo.marginBox(_6);
this.surface=dojox.gfx.createSurface(this.node,_8.w,_8.h);
},destroy:function(){
dojo.forEach(this.series,_3);
dojo.forEach(this.stack,_3);
df.forIn(this.axes,_3);
this.surface.destroy();
},getCoords:function(){
if(!this.coords){
this.coords=dojo.coords(this.node,true);
}
return this.coords;
},setTheme:function(_9){
this.theme=_9._clone();
this.dirty=true;
return this;
},addAxis:function(_a,_b){
var _c;
if(!_b||!("type" in _b)){
_c=new dc.axis2d.Default(this,_b);
}else{
_c=typeof _b.type=="string"?new dc.axis2d[_b.type](this,_b):new _b.type(this,_b);
}
_c.name=_a;
_c.dirty=true;
if(_a in this.axes){
this.axes[_a].destroy();
}
this.axes[_a]=_c;
this.dirty=true;
return this;
},getAxis:function(_d){
return this.axes[_d];
},removeAxis:function(_e){
if(_e in this.axes){
this.axes[_e].destroy();
delete this.axes[_e];
this.dirty=true;
}
return this;
},addPlot:function(_f,_10){
var _11;
if(!_10||!("type" in _10)){
_11=new dc.plot2d.Default(this,_10);
}else{
_11=typeof _10.type=="string"?new dc.plot2d[_10.type](this,_10):new _10.type(this,_10);
}
_11.name=_f;
_11.dirty=true;
if(_f in this.plots){
this.stack[this.plots[_f]].destroy();
this.stack[this.plots[_f]]=_11;
}else{
this.plots[_f]=this.stack.length;
this.stack.push(_11);
}
this.dirty=true;
return this;
},removePlot:function(_12){
if(_12 in this.plots){
var _13=this.plots[_12];
delete this.plots[_12];
this.stack[_13].destroy();
this.stack.splice(_13,1);
df.forIn(this.plots,function(idx,_14,_15){
if(idx>_13){
_15[_14]=idx-1;
}
});
this.dirty=true;
}
return this;
},addSeries:function(_16,_17,_18){
var run=new dc.Series(this,_17,_18);
if(_16 in this.runs){
this.series[this.runs[_16]].destroy();
this.series[this.runs[_16]]=run;
}else{
this.runs[_16]=this.series.length;
this.series.push(run);
}
run.name=_16;
this.dirty=true;
if(!("ymin" in run)&&"min" in run){
run.ymin=run.min;
}
if(!("ymax" in run)&&"max" in run){
run.ymax=run.max;
}
return this;
},removeSeries:function(_19){
if(_19 in this.runs){
var _1a=this.runs[_19],_1b=this.series[_1a].plot;
delete this.runs[_19];
this.series[_1a].destroy();
this.series.splice(_1a,1);
df.forIn(this.runs,function(idx,_1c,_1d){
if(idx>_1a){
_1d[_1c]=idx-1;
}
});
this.dirty=true;
}
return this;
},updateSeries:function(_1e,_1f){
if(_1e in this.runs){
var run=this.series[this.runs[_1e]];
run.data=_1f;
run.dirty=true;
this._invalidateDependentPlots(run.plot,false);
this._invalidateDependentPlots(run.plot,true);
}
return this;
},resize:function(_20,_21){
var box;
switch(arguments.length){
case 0:
box=dojo.marginBox(this.node);
break;
case 1:
box=_20;
break;
default:
box={w:_20,h:_21};
break;
}
dojo.marginBox(this.node,box);
this.surface.setDimensions(box.w,box.h);
this.dirty=true;
this.coords=null;
return this.render();
},getGeometry:function(){
var ret={};
df.forIn(this.axes,function(_22){
if(_22.initialized()){
ret[_22.name]={name:_22.name,vertical:_22.vertical,scaler:_22.scaler,ticks:_22.ticks};
}
});
return ret;
},setAxisWindow:function(_23,_24,_25){
var _26=this.axes[_23];
if(_26){
_26.setWindow(_24,_25);
}
return this;
},setWindow:function(sx,sy,dx,dy){
if(!("plotArea" in this)){
this.calculateGeometry();
}
df.forIn(this.axes,function(_27){
var _28,_29,_2a=_27.getScaler().bounds,s=_2a.span/(_2a.upper-_2a.lower);
if(_27.vertical){
_28=sy;
_29=dy/s/_28;
}else{
_28=sx;
_29=dx/s/_28;
}
_27.setWindow(_28,_29);
});
return this;
},calculateGeometry:function(){
if(this.dirty){
return this.fullGeometry();
}
dojo.forEach(this.stack,function(_2b){
if(_2b.dirty||(_2b.hAxis&&this.axes[_2b.hAxis].dirty)||(_2b.vAxis&&this.axes[_2b.vAxis].dirty)){
_2b.calculateAxes(this.plotArea);
}
},this);
return this;
},fullGeometry:function(){
this._makeDirty();
dojo.forEach(this.stack,_1);
if(!this.theme){
this.setTheme(new dojox.charting.Theme(dojox.charting._def));
}
dojo.forEach(this.series,function(run){
if(!(run.plot in this.plots)){
var _2c=new dc.plot2d.Default(this,{});
_2c.name=run.plot;
this.plots[run.plot]=this.stack.length;
this.stack.push(_2c);
}
this.stack[this.plots[run.plot]].addSeries(run);
},this);
dojo.forEach(this.stack,function(_2d){
if(_2d.hAxis){
_2d.setAxis(this.axes[_2d.hAxis]);
}
if(_2d.vAxis){
_2d.setAxis(this.axes[_2d.vAxis]);
}
},this);
var dim=this.dim=this.surface.getDimensions();
dim.width=dojox.gfx.normalizedLength(dim.width);
dim.height=dojox.gfx.normalizedLength(dim.height);
df.forIn(this.axes,_1);
dojo.forEach(this.stack,function(p){
p.calculateAxes(dim);
});
var _2e=this.offsets={l:0,r:0,t:0,b:0};
df.forIn(this.axes,function(_2f){
df.forIn(_2f.getOffsets(),function(o,i){
_2e[i]+=o;
});
});
df.forIn(this.margins,function(o,i){
_2e[i]+=o;
});
this.plotArea={width:dim.width-_2e.l-_2e.r,height:dim.height-_2e.t-_2e.b};
df.forIn(this.axes,_1);
dojo.forEach(this.stack,function(_30){
_30.calculateAxes(this.plotArea);
},this);
return this;
},render:function(){
if(this.theme){
this.theme.clear();
}
if(this.dirty){
return this.fullRender();
}
this.calculateGeometry();
df.forEachRev(this.stack,function(_31){
_31.render(this.dim,this.offsets);
},this);
df.forIn(this.axes,function(_32){
_32.render(this.dim,this.offsets);
},this);
this._makeClean();
if(this.surface.render){
this.surface.render();
}
return this;
},fullRender:function(){
this.fullGeometry();
var _33=this.offsets,dim=this.dim;
var _34=df.foldl(this.stack,"z + plot.getRequiredColors()",0);
this.theme.defineColors({num:_34,cache:false});
dojo.forEach(this.series,_2);
df.forIn(this.axes,_2);
dojo.forEach(this.stack,_2);
this.surface.clear();
var t=this.theme,_35=t.plotarea&&t.plotarea.fill,_36=t.plotarea&&t.plotarea.stroke;
if(_35){
this.surface.createRect({x:_33.l,y:_33.t,width:dim.width-_33.l-_33.r,height:dim.height-_33.t-_33.b}).setFill(_35);
}
if(_36){
this.surface.createRect({x:_33.l,y:_33.t,width:dim.width-_33.l-_33.r-1,height:dim.height-_33.t-_33.b-1}).setStroke(_36);
}
df.foldr(this.stack,function(z,_37){
return _37.render(dim,_33),0;
},0);
_35=this.fill?this.fill:(t.chart&&t.chart.fill);
_36=this.stroke?this.stroke:(t.chart&&t.chart.stroke);
if(_35=="inherit"){
var _38=this.node,_35=new dojo.Color(dojo.style(_38,"backgroundColor"));
while(_35.a==0&&_38!=document.documentElement){
_35=new dojo.Color(dojo.style(_38,"backgroundColor"));
_38=_38.parentNode;
}
}
if(_35){
if(_33.l){
this.surface.createRect({width:_33.l,height:dim.height+1}).setFill(_35);
}
if(_33.r){
this.surface.createRect({x:dim.width-_33.r,width:_33.r+1,height:dim.height+1}).setFill(_35);
}
if(_33.t){
this.surface.createRect({width:dim.width+1,height:_33.t}).setFill(_35);
}
if(_33.b){
this.surface.createRect({y:dim.height-_33.b,width:dim.width+1,height:_33.b+2}).setFill(_35);
}
}
if(_36){
this.surface.createRect({width:dim.width-1,height:dim.height-1}).setStroke(_36);
}
df.forIn(this.axes,function(_39){
_39.render(dim,_33);
});
this._makeClean();
if(this.surface.render){
this.surface.render();
}
return this;
},connectToPlot:function(_3a,_3b,_3c){
return _3a in this.plots?this.stack[this.plots[_3a]].connect(_3b,_3c):null;
},_makeClean:function(){
dojo.forEach(this.axes,_4);
dojo.forEach(this.stack,_4);
dojo.forEach(this.series,_4);
this.dirty=false;
},_makeDirty:function(){
dojo.forEach(this.axes,_5);
dojo.forEach(this.stack,_5);
dojo.forEach(this.series,_5);
this.dirty=true;
},_invalidateDependentPlots:function(_3d,_3e){
if(_3d in this.plots){
var _3f=this.stack[this.plots[_3d]],_40,_41=_3e?"vAxis":"hAxis";
if(_3f[_41]){
_40=this.axes[_3f[_41]];
if(_40&&_40.dependOnData()){
_40.dirty=true;
dojo.forEach(this.stack,function(p){
if(p[_41]&&p[_41]==_3f[_41]){
p.dirty=true;
}
});
}
}else{
_3f.dirty=true;
}
}
}});
})();
}
