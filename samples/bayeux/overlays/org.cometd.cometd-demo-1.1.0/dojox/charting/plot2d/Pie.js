/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dojox.charting.plot2d.Pie"]){
dojo._hasResource["dojox.charting.plot2d.Pie"]=true;
dojo.provide("dojox.charting.plot2d.Pie");
dojo.require("dojox.charting.Element");
dojo.require("dojox.charting.axis2d.common");
dojo.require("dojox.charting.plot2d.common");
dojo.require("dojox.lang.functional");
dojo.require("dojox.gfx");
(function(){
var df=dojox.lang.functional,du=dojox.lang.utils,dc=dojox.charting.plot2d.common,da=dojox.charting.axis2d.common,g=dojox.gfx;
dojo.declare("dojox.charting.plot2d.Pie",dojox.charting.Element,{defaultParams:{labels:true,ticks:false,fixed:true,precision:1,labelOffset:20,labelStyle:"default",htmlLabels:true},optionalParams:{font:"",fontColor:"",radius:0},constructor:function(_1,_2){
this.opt=dojo.clone(this.defaultParams);
du.updateWithObject(this.opt,_2);
du.updateWithPattern(this.opt,_2,this.optionalParams);
this.run=null;
this.dyn=[];
},destroy:function(){
this.resetEvents();
this.inherited(arguments);
},clear:function(){
this.dirty=true;
this.dyn=[];
this.run=null;
return this;
},setAxis:function(_3){
return this;
},addSeries:function(_4){
this.run=_4;
return this;
},calculateAxes:function(_5){
return this;
},getRequiredColors:function(){
return this.run?this.run.data.length:0;
},plotEvent:function(o){
},connect:function(_6,_7){
this.dirty=true;
return dojo.connect(this,"plotEvent",_6,_7);
},events:function(){
var ls=this.plotEvent._listeners;
if(!ls||!ls.length){
return false;
}
for(var i in ls){
if(!(i in Array.prototype)){
return true;
}
}
return false;
},resetEvents:function(){
this.plotEvent({type:"onplotreset",plot:this});
},_connectEvents:function(_8,o){
_8.connect("onmouseover",this,function(e){
o.type="onmouseover";
o.event=e;
this.plotEvent(o);
});
_8.connect("onmouseout",this,function(e){
o.type="onmouseout";
o.event=e;
this.plotEvent(o);
});
_8.connect("onclick",this,function(e){
o.type="onclick";
o.event=e;
this.plotEvent(o);
});
},render:function(_9,_a){
if(!this.dirty){
return this;
}
this.dirty=false;
this.cleanGroup();
var s=this.group,_b,t=this.chart.theme;
this.resetEvents();
if(!this.run||!this.run.data.length){
return this;
}
var rx=(_9.width-_a.l-_a.r)/2,ry=(_9.height-_a.t-_a.b)/2,r=Math.min(rx,ry),_c="font" in this.opt?this.opt.font:t.axis.font,_d=_c?g.normalizedLength(g.splitFontString(_c).size):0,_e="fontColor" in this.opt?this.opt.fontColor:t.axis.fontColor,_f=0,_10,_11,_12,_13,_14,_15,run=this.run.data,_16=this.events();
if(typeof run[0]=="number"){
_11=df.map(run,"Math.max(x, 0)");
if(df.every(_11,"<= 0")){
return this;
}
_12=df.map(_11,"/this",df.foldl(_11,"+",0));
if(this.opt.labels){
_13=dojo.map(_12,function(x){
return x>0?this._getLabel(x*100)+"%":"";
},this);
}
}else{
_11=df.map(run,"Math.max(x.y, 0)");
if(df.every(_11,"<= 0")){
return this;
}
_12=df.map(_11,"/this",df.foldl(_11,"+",0));
if(this.opt.labels){
_13=dojo.map(_12,function(x,i){
if(x<=0){
return "";
}
var v=run[i];
return "text" in v?v.text:this._getLabel(x*100)+"%";
},this);
}
}
if(this.opt.labels){
_14=df.foldl1(df.map(_13,function(_17){
return dojox.gfx._base._getTextBox(_17,{font:_c}).w;
},this),"Math.max(a, b)")/2;
if(this.opt.labelOffset<0){
r=Math.min(rx-2*_14,ry-_d)+this.opt.labelOffset;
}
_15=r-this.opt.labelOffset;
}
if("radius" in this.opt){
r=this.opt.radius;
_15=r-this.opt.labelOffset;
}
var _18={cx:_a.l+rx,cy:_a.t+ry,r:r};
this.dyn=[];
dojo.some(_12,function(_19,i){
if(_19<=0){
return false;
}
var v=run[i];
if(_19>=1){
var _1a,_1b,_1c;
if(typeof v=="object"){
_1a="color" in v?v.color:new dojo.Color(t.next("color"));
_1b="fill" in v?v.fill:dc.augmentFill(t.series.fill,_1a);
_1c="stroke" in v?v.stroke:dc.augmentStroke(t.series.stroke,_1a);
}else{
_1a=new dojo.Color(t.next("color"));
_1b=dc.augmentFill(t.series.fill,_1a);
_1c=dc.augmentStroke(t.series.stroke,_1a);
}
var _1d=s.createCircle(_18).setFill(_1b).setStroke(_1c);
this.dyn.push({color:_1a,fill:_1b,stroke:_1c});
if(_16){
var o={element:"slice",index:i,run:this.run,plot:this,shape:_1d,x:i,y:typeof v=="number"?v:v.y,cx:_18.cx,cy:_18.cy,cr:r};
this._connectEvents(_1d,o);
}
return true;
}
var end=_f+_19*2*Math.PI;
if(i+1==_12.length){
end=2*Math.PI;
}
var _1e=end-_f,x1=_18.cx+r*Math.cos(_f),y1=_18.cy+r*Math.sin(_f),x2=_18.cx+r*Math.cos(end),y2=_18.cy+r*Math.sin(end);
var _1a,_1b,_1c;
if(typeof v=="object"){
_1a="color" in v?v.color:new dojo.Color(t.next("color"));
_1b="fill" in v?v.fill:dc.augmentFill(t.series.fill,_1a);
_1c="stroke" in v?v.stroke:dc.augmentStroke(t.series.stroke,_1a);
}else{
_1a=new dojo.Color(t.next("color"));
_1b=dc.augmentFill(t.series.fill,_1a);
_1c=dc.augmentStroke(t.series.stroke,_1a);
}
var _1d=s.createPath({}).moveTo(_18.cx,_18.cy).lineTo(x1,y1).arcTo(r,r,0,_1e>Math.PI,true,x2,y2).lineTo(_18.cx,_18.cy).closePath().setFill(_1b).setStroke(_1c);
this.dyn.push({color:_1a,fill:_1b,stroke:_1c});
if(_16){
var o={element:"slice",index:i,run:this.run,plot:this,shape:_1d,x:i,y:typeof v=="number"?v:v.y,cx:_18.cx,cy:_18.cy,cr:r};
this._connectEvents(_1d,o);
}
_f=end;
return false;
},this);
if(this.opt.labels){
_f=0;
dojo.some(_12,function(_1f,i){
if(_1f<=0){
return false;
}
if(_1f>=1){
var v=run[i],_20=da.createText[this.opt.htmlLabels&&dojox.gfx.renderer!="vml"?"html":"gfx"](this.chart,s,_18.cx,_18.cy+_d/2,"middle",_13[i],_c,(typeof v=="object"&&"fontColor" in v)?v.fontColor:_e);
if(this.opt.htmlLabels){
this.htmlElements.push(_20);
}
return true;
}
var end=_f+_1f*2*Math.PI,v=run[i];
if(i+1==_12.length){
end=2*Math.PI;
}
var _21=(_f+end)/2,x=_18.cx+_15*Math.cos(_21),y=_18.cy+_15*Math.sin(_21)+_d/2;
var _20=da.createText[this.opt.htmlLabels&&dojox.gfx.renderer!="vml"?"html":"gfx"](this.chart,s,x,y,"middle",_13[i],_c,(typeof v=="object"&&"fontColor" in v)?v.fontColor:_e);
if(this.opt.htmlLabels){
this.htmlElements.push(_20);
}
_f=end;
return false;
},this);
}
return this;
},_getLabel:function(_22){
return this.opt.fixed?_22.toFixed(this.opt.precision):_22.toString();
}});
})();
}
