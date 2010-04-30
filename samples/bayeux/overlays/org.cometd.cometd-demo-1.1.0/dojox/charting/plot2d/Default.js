/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dojox.charting.plot2d.Default"]){
dojo._hasResource["dojox.charting.plot2d.Default"]=true;
dojo.provide("dojox.charting.plot2d.Default");
dojo.require("dojox.charting.plot2d.common");
dojo.require("dojox.charting.plot2d.Base");
dojo.require("dojox.lang.utils");
dojo.require("dojox.lang.functional");
dojo.require("dojox.lang.functional.reversed");
(function(){
var df=dojox.lang.functional,du=dojox.lang.utils,dc=dojox.charting.plot2d.common,_1=df.lambda("item.purgeGroup()");
dojo.declare("dojox.charting.plot2d.Default",dojox.charting.plot2d.Base,{defaultParams:{hAxis:"x",vAxis:"y",lines:true,areas:false,markers:false,shadows:0,tension:0},optionalParams:{},constructor:function(_2,_3){
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
var t=this.chart.theme,_8,_9,_a,_b,_c=this.events();
this.resetEvents();
for(var i=this.series.length-1;i>=0;--i){
var _d=this.series[i];
if(!this.dirty&&!_d.dirty){
continue;
}
_d.cleanGroup();
if(!_d.data.length){
_d.dirty=false;
continue;
}
var s=_d.group,_e,ht=this._hScaler.scaler.getTransformerFromModel(this._hScaler),vt=this._vScaler.scaler.getTransformerFromModel(this._vScaler);
if(typeof _d.data[0]=="number"){
_e=dojo.map(_d.data,function(v,i){
return {x:ht(i+1)+_6.l,y:_5.height-_6.b-vt(v)};
},this);
}else{
_e=dojo.map(_d.data,function(v,i){
return {x:ht(v.x)+_6.l,y:_5.height-_6.b-vt(v.y)};
},this);
}
if(!_d.fill||!_d.stroke){
_a=_d.dyn.color=new dojo.Color(t.next("color"));
}
var _f=this.opt.tension?dc.curve(_e,this.opt.tension):"";
if(this.opt.areas){
var _10=_d.fill?_d.fill:dc.augmentFill(t.series.fill,_a);
var _11=dojo.clone(_e);
if(this.opt.tension){
var _12="L"+_11[_11.length-1].x+","+(_5.height-_6.b)+" L"+_11[0].x+","+(_5.height-_6.b)+" L"+_11[0].x+","+_11[0].y;
_d.dyn.fill=s.createPath(_f+" "+_12).setFill(_10).getFill();
}else{
_11.push({x:_e[_e.length-1].x,y:_5.height-_6.b});
_11.push({x:_e[0].x,y:_5.height-_6.b});
_11.push(_e[0]);
_d.dyn.fill=s.createPolyline(_11).setFill(_10).getFill();
}
}
if(this.opt.lines||this.opt.markers){
_8=_d.dyn.stroke=_d.stroke?dc.makeStroke(_d.stroke):dc.augmentStroke(t.series.stroke,_a);
if(_d.outline||t.series.outline){
_9=_d.dyn.outline=dc.makeStroke(_d.outline?_d.outline:t.series.outline);
_9.width=2*_9.width+_8.width;
}
}
if(this.opt.markers){
_b=_d.dyn.marker=_d.marker?_d.marker:t.next("marker");
}
var _13=null,_14=null,_15=null;
if(this.opt.shadows&&_8){
var sh=this.opt.shadows,_16=new dojo.Color([0,0,0,0.3]),_17=dojo.map(_e,function(c){
return {x:c.x+sh.dx,y:c.y+sh.dy};
}),_18=dojo.clone(_9?_9:_8);
_18.color=_16;
_18.width+=sh.dw?sh.dw:0;
if(this.opt.lines){
if(this.opt.tension){
_d.dyn.shadow=s.createPath(dc.curve(_17,this.opt.tension)).setStroke(_18).getStroke();
}else{
_d.dyn.shadow=s.createPolyline(_17).setStroke(_18).getStroke();
}
}
if(this.opt.markers){
_15=dojo.map(_17,function(c){
return s.createPath("M"+c.x+" "+c.y+" "+_b).setStroke(_18).setFill(_16);
},this);
}
}
if(this.opt.lines){
if(_9){
if(this.opt.tension){
_d.dyn.outline=s.createPath(_f).setStroke(_9).getStroke();
}else{
_d.dyn.outline=s.createPolyline(_e).setStroke(_9).getStroke();
}
}
if(this.opt.tension){
_d.dyn.stroke=s.createPath(_f).setStroke(_8).getStroke();
}else{
_d.dyn.stroke=s.createPolyline(_e).setStroke(_8).getStroke();
}
}
if(this.opt.markers){
_13=new Array(_e.length);
_14=new Array(_e.length);
dojo.forEach(_e,function(c,i){
var _19="M"+c.x+" "+c.y+" "+_b;
if(_9){
_14[i]=s.createPath(_19).setStroke(_9);
}
_13[i]=s.createPath(_19).setStroke(_8).setFill(_8.color);
},this);
if(_c){
dojo.forEach(_13,function(s,i){
var o={element:"marker",index:i,run:_d,plot:this,hAxis:this.hAxis||null,vAxis:this.vAxis||null,shape:s,outline:_14[i]||null,shadow:_15&&_15[i]||null,cx:_e[i].x,cy:_e[i].y};
if(typeof _d.data[0]=="number"){
o.x=i+1;
o.y=_d.data[i];
}else{
o.x=_d.data[i].x;
o.y=_d.data[i].y;
}
this._connectEvents(s,o);
},this);
}
}
_d.dirty=false;
}
this.dirty=false;
return this;
}});
})();
}
