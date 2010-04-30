/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dojox.charting.plot2d.Grid"]){
dojo._hasResource["dojox.charting.plot2d.Grid"]=true;
dojo.provide("dojox.charting.plot2d.Grid");
dojo.require("dojox.charting.Element");
dojo.require("dojox.charting.plot2d.common");
dojo.require("dojox.lang.functional");
(function(){
var du=dojox.lang.utils;
dojo.declare("dojox.charting.plot2d.Grid",dojox.charting.Element,{defaultParams:{hAxis:"x",vAxis:"y",hMajorLines:true,hMinorLines:false,vMajorLines:true,vMinorLines:false,hStripes:"none",vStripes:"none"},optionalParams:{},constructor:function(_1,_2){
this.opt=dojo.clone(this.defaultParams);
du.updateWithObject(this.opt,_2);
this.hAxis=this.opt.hAxis;
this.vAxis=this.opt.vAxis;
this.dirty=true;
},clear:function(){
this._hAxis=null;
this._vAxis=null;
this.dirty=true;
return this;
},setAxis:function(_3){
if(_3){
this[_3.vertical?"_vAxis":"_hAxis"]=_3;
}
return this;
},addSeries:function(_4){
return this;
},calculateAxes:function(_5){
return this;
},isDirty:function(){
return this.dirty||this._hAxis&&this._hAxis.dirty||this._vAxis&&this._vAxis.dirty;
},getRequiredColors:function(){
return 0;
},render:function(_6,_7){
this.dirty=this.isDirty();
if(!this.dirty){
return this;
}
this.cleanGroup();
var s=this.group,ta=this.chart.theme.axis;
try{
var _8=this._vAxis.getScaler(),vt=_8.scaler.getTransformerFromModel(_8),_9=this._vAxis.getTicks();
if(this.opt.hMinorLines){
dojo.forEach(_9.minor,function(_a){
var y=_6.height-_7.b-vt(_a.value);
s.createLine({x1:_7.l,y1:y,x2:_6.width-_7.r,y2:y}).setStroke(ta.minorTick);
});
}
if(this.opt.hMajorLines){
dojo.forEach(_9.major,function(_b){
var y=_6.height-_7.b-vt(_b.value);
s.createLine({x1:_7.l,y1:y,x2:_6.width-_7.r,y2:y}).setStroke(ta.majorTick);
});
}
}
catch(e){
}
try{
var _c=this._hAxis.getScaler(),ht=_c.scaler.getTransformerFromModel(_c),_9=this._hAxis.getTicks();
if(_9&&this.opt.vMinorLines){
dojo.forEach(_9.minor,function(_d){
var x=_7.l+ht(_d.value);
s.createLine({x1:x,y1:_7.t,x2:x,y2:_6.height-_7.b}).setStroke(ta.minorTick);
});
}
if(_9&&this.opt.vMajorLines){
dojo.forEach(_9.major,function(_e){
var x=_7.l+ht(_e.value);
s.createLine({x1:x,y1:_7.t,x2:x,y2:_6.height-_7.b}).setStroke(ta.majorTick);
});
}
}
catch(e){
}
this.dirty=false;
return this;
}});
})();
}
