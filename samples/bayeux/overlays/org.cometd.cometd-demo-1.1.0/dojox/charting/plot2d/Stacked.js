/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dojox.charting.plot2d.Stacked"]){
dojo._hasResource["dojox.charting.plot2d.Stacked"]=true;
dojo.provide("dojox.charting.plot2d.Stacked");
dojo.require("dojox.charting.plot2d.common");
dojo.require("dojox.charting.plot2d.Default");
dojo.require("dojox.lang.functional");
dojo.require("dojox.lang.functional.sequence");
dojo.require("dojox.lang.functional.reversed");
(function(){
var df=dojox.lang.functional,dc=dojox.charting.plot2d.common,_1=df.lambda("item.purgeGroup()");
dojo.declare("dojox.charting.plot2d.Stacked",dojox.charting.plot2d.Default,{calculateAxes:function(_2){
var _3=dc.collectStackedStats(this.series);
this._maxRunLength=_3.hmax;
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
var v=_7.data[j];
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
df.forEachRev(this.series,function(_8){
_8.cleanGroup(s);
});
}
var t=this.chart.theme,_9,_a,_b,_c,_d=this.events(),ht=this._hScaler.scaler.getTransformerFromModel(this._hScaler),vt=this._vScaler.scaler.getTransformerFromModel(this._vScaler);
this.resetEvents();
for(var i=this.series.length-1;i>=0;--i){
var _7=this.series[i];
if(!this.dirty&&!_7.dirty){
continue;
}
_7.cleanGroup();
var s=_7.group,_e=dojo.map(_6,function(v,i){
return {x:ht(i+1)+_5.l,y:_4.height-_5.b-vt(v)};
},this);
if(!_7.fill||!_7.stroke){
_b=new dojo.Color(t.next("color"));
}
var _f=this.opt.tension?dc.curve(_e,this.opt.tension):"";
if(this.opt.areas){
var _10=dojo.clone(_e);
var _11=_7.fill?_7.fill:dc.augmentFill(t.series.fill,_b);
if(this.opt.tension){
var p=dc.curve(_10,this.opt.tension);
p+=" L"+_e[_e.length-1].x+","+(_4.height-_5.b)+" L"+_e[0].x+","+(_4.height-_5.b)+" L"+_e[0].x+","+_e[0].y;
_7.dyn.fill=s.createPath(p).setFill(_11).getFill();
}else{
_10.push({x:_e[_e.length-1].x,y:_4.height-_5.b});
_10.push({x:_e[0].x,y:_4.height-_5.b});
_10.push(_e[0]);
_7.dyn.fill=s.createPolyline(_10).setFill(_11).getFill();
}
}
if(this.opt.lines||this.opt.markers){
_9=_7.stroke?dc.makeStroke(_7.stroke):dc.augmentStroke(t.series.stroke,_b);
if(_7.outline||t.series.outline){
_a=dc.makeStroke(_7.outline?_7.outline:t.series.outline);
_a.width=2*_a.width+_9.width;
}
}
if(this.opt.markers){
_c=_7.dyn.marker=_7.marker?_7.marker:t.next("marker");
}
var _12,_13,_14;
if(this.opt.shadows&&_9){
var sh=this.opt.shadows,_15=new dojo.Color([0,0,0,0.3]),_16=dojo.map(_e,function(c){
return {x:c.x+sh.dx,y:c.y+sh.dy};
}),_17=dojo.clone(_a?_a:_9);
_17.color=_15;
_17.width+=sh.dw?sh.dw:0;
if(this.opt.lines){
if(this.opt.tension){
_7.dyn.shadow=s.createPath(dc.curve(_16,this.opt.tension)).setStroke(_17).getStroke();
}else{
_7.dyn.shadow=s.createPolyline(_16).setStroke(_17).getStroke();
}
}
if(this.opt.markers){
_14=dojo.map(_16,function(c){
return s.createPath("M"+c.x+" "+c.y+" "+_c).setStroke(_17).setFill(_15);
},this);
}
}
if(this.opt.lines){
if(_a){
if(this.opt.tension){
_7.dyn.outline=s.createPath(_f).setStroke(_a).getStroke();
}else{
_7.dyn.outline=s.createPolyline(_e).setStroke(_a).getStroke();
}
}
if(this.opt.tension){
_7.dyn.stroke=s.createPath(_f).setStroke(_9).getStroke();
}else{
_7.dyn.stroke=s.createPolyline(_e).setStroke(_9).getStroke();
}
}
if(this.opt.markers){
_12=new Array(_e.length);
_13=new Array(_e.length);
dojo.forEach(_e,function(c,i){
var _18="M"+c.x+" "+c.y+" "+_c;
if(_a){
_13[i]=s.createPath(_18).setStroke(_a);
}
_12[i]=s.createPath(_18).setStroke(_9).setFill(_9.color);
},this);
if(_d){
dojo.forEach(_12,function(s,i){
var o={element:"marker",index:i,run:_7,plot:this,hAxis:this.hAxis||null,vAxis:this.vAxis||null,shape:s,outline:_13[i]||null,shadow:_14&&_14[i]||null,cx:_e[i].x,cy:_e[i].y,x:i+1,y:_7.data[i]};
this._connectEvents(s,o);
},this);
}
}
_7.dirty=false;
for(var j=0;j<_7.data.length;++j){
var v=_7.data[j];
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
