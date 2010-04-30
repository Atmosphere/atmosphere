/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dojox.charting.axis2d.Default"]){
dojo._hasResource["dojox.charting.axis2d.Default"]=true;
dojo.provide("dojox.charting.axis2d.Default");
dojo.require("dojox.charting.scaler.linear");
dojo.require("dojox.charting.axis2d.common");
dojo.require("dojox.charting.axis2d.Base");
dojo.require("dojo.colors");
dojo.require("dojo.string");
dojo.require("dojox.gfx");
dojo.require("dojox.lang.functional");
dojo.require("dojox.lang.utils");
(function(){
var dc=dojox.charting,df=dojox.lang.functional,du=dojox.lang.utils,g=dojox.gfx,_1=dc.scaler.linear,_2=4;
dojo.declare("dojox.charting.axis2d.Default",dojox.charting.axis2d.Base,{defaultParams:{vertical:false,fixUpper:"none",fixLower:"none",natural:false,leftBottom:true,includeZero:false,fixed:true,majorLabels:true,minorTicks:true,minorLabels:true,microTicks:false,htmlLabels:true},optionalParams:{min:0,max:1,from:0,to:1,majorTickStep:4,minorTickStep:2,microTickStep:1,labels:[],labelFunc:null,maxLabelSize:0,stroke:{},majorTick:{},minorTick:{},microTick:{},font:"",fontColor:""},constructor:function(_3,_4){
this.opt=dojo.delegate(this.defaultParams,_4);
du.updateWithPattern(this.opt,_4,this.optionalParams);
},dependOnData:function(){
return !("min" in this.opt)||!("max" in this.opt);
},clear:function(){
delete this.scaler;
delete this.ticks;
this.dirty=true;
return this;
},initialized:function(){
return "scaler" in this&&!(this.dirty&&this.dependOnData());
},setWindow:function(_5,_6){
this.scale=_5;
this.offset=_6;
return this.clear();
},getWindowScale:function(){
return "scale" in this?this.scale:1;
},getWindowOffset:function(){
return "offset" in this?this.offset:0;
},_groupLabelWidth:function(_7,_8){
if(_7[0]["text"]){
_7=df.map(_7,function(_9){
return _9.text;
});
}
var s=_7.join("<br>");
return dojox.gfx._base._getTextBox(s,{font:_8}).w||0;
},calculate:function(_a,_b,_c,_d){
if(this.initialized()){
return this;
}
var o=this.opt;
this.labels="labels" in o?o.labels:_d;
this.scaler=_1.buildScaler(_a,_b,_c,o);
var _e=this.scaler.bounds;
if("scale" in this){
o.from=_e.lower+this.offset;
o.to=(_e.upper-_e.lower)/this.scale+o.from;
if(!isFinite(o.from)||isNaN(o.from)||!isFinite(o.to)||isNaN(o.to)||o.to-o.from>=_e.upper-_e.lower){
delete o.from;
delete o.to;
delete this.scale;
delete this.offset;
}else{
if(o.from<_e.lower){
o.to+=_e.lower-o.from;
o.from=_e.lower;
}else{
if(o.to>_e.upper){
o.from+=_e.upper-o.to;
o.to=_e.upper;
}
}
this.offset=o.from-_e.lower;
}
this.scaler=_1.buildScaler(_a,_b,_c,o);
_e=this.scaler.bounds;
if(this.scale==1&&this.offset==0){
delete this.scale;
delete this.offset;
}
}
var _f=0,ta=this.chart.theme.axis,_10="font" in o?o.font:ta.font,_11=_10?g.normalizedLength(g.splitFontString(_10).size):0;
if(this.vertical){
if(_11){
_f=_11+_2;
}
}else{
if(_11){
var _12,i;
if(o.labelFunc&&o.maxLabelSize){
_12=o.maxLabelSize;
}else{
if(this.labels){
_12=this._groupLabelWidth(this.labels,_10);
}else{
var _13=Math.ceil(Math.log(Math.max(Math.abs(_e.from),Math.abs(_e.to)))/Math.LN10),t=[];
if(_e.from<0||_e.to<0){
t.push("-");
}
t.push(dojo.string.rep("9",_13));
var _14=Math.floor(Math.log(_e.to-_e.from)/Math.LN10);
if(_14>0){
t.push(".");
for(i=0;i<_14;++i){
t.push("9");
}
}
_12=dojox.gfx._base._getTextBox(t.join(""),{font:_10}).w;
}
}
_f=_12+_2;
}
}
this.scaler.minMinorStep=_f;
this.ticks=_1.buildTicks(this.scaler,o);
return this;
},getScaler:function(){
return this.scaler;
},getTicks:function(){
return this.ticks;
},getOffsets:function(){
var o=this.opt;
var _15={l:0,r:0,t:0,b:0},_16,a,b,c,d,gl=dc.scaler.common.getNumericLabel,_17=0,ta=this.chart.theme.axis,_18="font" in o?o.font:ta.font,_19="majorTick" in o?o.majorTick:ta.majorTick,_1a="minorTick" in o?o.minorTick:ta.minorTick,_1b=_18?g.normalizedLength(g.splitFontString(_18).size):0,s=this.scaler;
if(!s){
return _15;
}
var ma=s.major,mi=s.minor;
if(this.vertical){
if(_1b){
if(o.labelFunc&&o.maxLabelSize){
_16=o.maxLabelSize;
}else{
if(this.labels){
_16=this._groupLabelWidth(this.labels,_18);
}else{
_16=this._groupLabelWidth([gl(ma.start,ma.prec,o),gl(ma.start+ma.count*ma.tick,ma.prec,o),gl(mi.start,mi.prec,o),gl(mi.start+mi.count*mi.tick,mi.prec,o)],_18);
}
}
_17=_16+_2;
}
_17+=_2+Math.max(_19.length,_1a.length);
_15[o.leftBottom?"l":"r"]=_17;
_15.t=_15.b=_1b/2;
}else{
if(_1b){
_17=_1b+_2;
}
_17+=_2+Math.max(_19.length,_1a.length);
_15[o.leftBottom?"b":"t"]=_17;
if(_1b){
if(o.labelFunc&&o.maxLabelSize){
_16=o.maxLabelSize;
}else{
if(this.labels){
_16=this._groupLabelWidth(this.labels,_18);
}else{
_16=this._groupLabelWidth([gl(ma.start,ma.prec,o),gl(ma.start+ma.count*ma.tick,ma.prec,o),gl(mi.start,mi.prec,o),gl(mi.start+mi.count*mi.tick,mi.prec,o)],_18);
}
}
_15.l=_15.r=_16/2;
}
}
if(_16){
this._cachedLabelWidth=_16;
}
return _15;
},render:function(dim,_1c){
if(!this.dirty){
return this;
}
var o=this.opt;
var _1d,_1e,_1f,_20,_21,_22,ta=this.chart.theme.axis,_23="stroke" in o?o.stroke:ta.stroke,_24="majorTick" in o?o.majorTick:ta.majorTick,_25="minorTick" in o?o.minorTick:ta.minorTick,_26="microTick" in o?o.microTick:ta.minorTick,_27="font" in o?o.font:ta.font,_28="fontColor" in o?o.fontColor:ta.fontColor,_29=Math.max(_24.length,_25.length),_2a=_27?g.normalizedLength(g.splitFontString(_27).size):0;
if(this.vertical){
_1d={y:dim.height-_1c.b};
_1e={y:_1c.t};
_1f={x:0,y:-1};
if(o.leftBottom){
_1d.x=_1e.x=_1c.l;
_20={x:-1,y:0};
_22="end";
}else{
_1d.x=_1e.x=dim.width-_1c.r;
_20={x:1,y:0};
_22="start";
}
_21={x:_20.x*(_29+_2),y:_2a*0.4};
}else{
_1d={x:_1c.l};
_1e={x:dim.width-_1c.r};
_1f={x:1,y:0};
_22="middle";
if(o.leftBottom){
_1d.y=_1e.y=dim.height-_1c.b;
_20={x:0,y:1};
_21={y:_29+_2+_2a};
}else{
_1d.y=_1e.y=_1c.t;
_20={x:0,y:-1};
_21={y:-_29-_2};
}
_21.x=0;
}
this.cleanGroup();
try{
var s=this.group,c=this.scaler,t=this.ticks,_2b,f=_1.getTransformerFromModel(this.scaler),_2c=(dojox.gfx.renderer=="canvas"),_2d=_2c||this.opt.htmlLabels&&!dojo.isIE&&!dojo.isOpera?"html":"gfx",dx=_20.x*_24.length,dy=_20.y*_24.length;
s.createLine({x1:_1d.x,y1:_1d.y,x2:_1e.x,y2:_1e.y}).setStroke(_23);
dojo.forEach(t.major,function(_2e){
var _2f=f(_2e.value),_30,x=_1d.x+_1f.x*_2f,y=_1d.y+_1f.y*_2f;
s.createLine({x1:x,y1:y,x2:x+dx,y2:y+dy}).setStroke(_24);
if(_2e.label){
_30=dc.axis2d.common.createText[_2d](this.chart,s,x+_21.x,y+_21.y,_22,_2e.label,_27,_28,this._cachedLabelWidth);
if(_2d=="html"){
this.htmlElements.push(_30);
}
}
},this);
dx=_20.x*_25.length;
dy=_20.y*_25.length;
_2b=c.minMinorStep<=c.minor.tick*c.bounds.scale;
dojo.forEach(t.minor,function(_31){
var _32=f(_31.value),_33,x=_1d.x+_1f.x*_32,y=_1d.y+_1f.y*_32;
s.createLine({x1:x,y1:y,x2:x+dx,y2:y+dy}).setStroke(_25);
if(_2b&&_31.label){
_33=dc.axis2d.common.createText[_2d](this.chart,s,x+_21.x,y+_21.y,_22,_31.label,_27,_28,this._cachedLabelWidth);
if(_2d=="html"){
this.htmlElements.push(_33);
}
}
},this);
dx=_20.x*_26.length;
dy=_20.y*_26.length;
dojo.forEach(t.micro,function(_34){
var _35=f(_34.value),_36,x=_1d.x+_1f.x*_35,y=_1d.y+_1f.y*_35;
s.createLine({x1:x,y1:y,x2:x+dx,y2:y+dy}).setStroke(_26);
},this);
}
catch(e){
}
this.dirty=false;
return this;
}});
})();
}
