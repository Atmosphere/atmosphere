/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dojox.gfx.silverlight"]){
dojo._hasResource["dojox.gfx.silverlight"]=true;
dojo.provide("dojox.gfx.silverlight");
dojo.require("dojox.gfx._base");
dojo.require("dojox.gfx.shape");
dojo.require("dojox.gfx.path");
dojo.experimental("dojox.gfx.silverlight");
dojox.gfx.silverlight.dasharray={solid:"none",shortdash:[4,1],shortdot:[1,1],shortdashdot:[4,1,1,1],shortdashdotdot:[4,1,1,1,1,1],dot:[1,3],dash:[4,3],longdash:[8,3],dashdot:[4,3,1,3],longdashdot:[8,3,1,3],longdashdotdot:[8,3,1,3,1,3]};
dojox.gfx.silverlight.fontweight={normal:400,bold:700};
dojox.gfx.silverlight.caps={butt:"Flat",round:"Round",square:"Square"};
dojox.gfx.silverlight.joins={bevel:"Bevel",round:"Round"};
dojox.gfx.silverlight.fonts={serif:"Times New Roman",times:"Times New Roman","sans-serif":"Arial",helvetica:"Arial",monotone:"Courier New",courier:"Courier New"};
dojox.gfx.silverlight.hexColor=function(_1){
var c=dojox.gfx.normalizeColor(_1),t=c.toHex(),a=Math.round(c.a*255);
a=(a<0?0:a>255?255:a).toString(16);
return "#"+(a.length<2?"0"+a:a)+t.slice(1);
};
dojo.extend(dojox.gfx.Shape,{setFill:function(_2){
var p=this.rawNode.getHost().content,r=this.rawNode,f;
if(!_2){
this.fillStyle=null;
this._setFillAttr(null);
return this;
}
if(typeof (_2)=="object"&&"type" in _2){
switch(_2.type){
case "linear":
this.fillStyle=f=dojox.gfx.makeParameters(dojox.gfx.defaultLinearGradient,_2);
var _3=p.createFromXaml("<LinearGradientBrush/>");
_3.mappingMode="Absolute";
_3.startPoint=f.x1+","+f.y1;
_3.endPoint=f.x2+","+f.y2;
dojo.forEach(f.colors,function(c){
var t=p.createFromXaml("<GradientStop/>");
t.offset=c.offset;
t.color=dojox.gfx.silverlight.hexColor(c.color);
_3.gradientStops.add(t);
});
this._setFillAttr(_3);
break;
case "radial":
this.fillStyle=f=dojox.gfx.makeParameters(dojox.gfx.defaultRadialGradient,_2);
var _4=p.createFromXaml("<RadialGradientBrush/>"),c=dojox.gfx.matrix.multiplyPoint(dojox.gfx.matrix.invert(this._getAdjustedMatrix()),f.cx,f.cy),pt=c.x+","+c.y;
_4.mappingMode="Absolute";
_4.gradientOrigin=pt;
_4.center=pt;
_4.radiusX=_4.radiusY=f.r;
dojo.forEach(f.colors,function(c){
var t=p.createFromXaml("<GradientStop/>");
t.offset=c.offset;
t.color=dojox.gfx.silverlight.hexColor(c.color);
_4.gradientStops.add(t);
});
this._setFillAttr(_4);
break;
case "pattern":
this.fillStyle=null;
this._setFillAttr(null);
break;
}
return this;
}
this.fillStyle=f=dojox.gfx.normalizeColor(_2);
var _5=p.createFromXaml("<SolidColorBrush/>");
_5.color=f.toHex();
_5.opacity=f.a;
this._setFillAttr(_5);
return this;
},_setFillAttr:function(f){
this.rawNode.fill=f;
},setStroke:function(_6){
var p=this.rawNode.getHost().content,r=this.rawNode;
if(!_6){
this.strokeStyle=null;
r.stroke=null;
return this;
}
if(typeof _6=="string"||dojo.isArray(_6)||_6 instanceof dojo.Color){
_6={color:_6};
}
var s=this.strokeStyle=dojox.gfx.makeParameters(dojox.gfx.defaultStroke,_6);
s.color=dojox.gfx.normalizeColor(s.color);
if(s){
var _7=p.createFromXaml("<SolidColorBrush/>");
_7.color=s.color.toHex();
_7.opacity=s.color.a;
r.stroke=_7;
r.strokeThickness=s.width;
r.strokeStartLineCap=r.strokeEndLineCap=r.strokeDashCap=dojox.gfx.silverlight.caps[s.cap];
if(typeof s.join=="number"){
r.strokeLineJoin="Miter";
r.strokeMiterLimit=s.join;
}else{
r.strokeLineJoin=dojox.gfx.silverlight.joins[s.join];
}
var da=s.style.toLowerCase();
if(da in dojox.gfx.silverlight.dasharray){
da=dojox.gfx.silverlight.dasharray[da];
}
if(da instanceof Array){
da=dojo.clone(da);
var i;
if(s.cap!="butt"){
for(i=0;i<da.length;i+=2){
--da[i];
if(da[i]<1){
da[i]=1;
}
}
for(i=1;i<da.length;i+=2){
++da[i];
}
}
r.strokeDashArray=da.join(",");
}else{
r.strokeDashArray=null;
}
}
return this;
},_getParentSurface:function(){
var _8=this.parent;
for(;_8&&!(_8 instanceof dojox.gfx.Surface);_8=_8.parent){
}
return _8;
},_applyTransform:function(){
var tm=this._getAdjustedMatrix(),r=this.rawNode;
if(tm){
var p=this.rawNode.getHost().content,m=p.createFromXaml("<MatrixTransform/>"),mm=p.createFromXaml("<Matrix/>");
mm.m11=tm.xx;
mm.m21=tm.xy;
mm.m12=tm.yx;
mm.m22=tm.yy;
mm.offsetX=tm.dx;
mm.offsetY=tm.dy;
m.matrix=mm;
r.renderTransform=m;
}else{
r.renderTransform=null;
}
return this;
},setRawNode:function(_9){
_9.fill=null;
_9.stroke=null;
this.rawNode=_9;
},_moveToFront:function(){
var c=this.parent.rawNode.children,r=this.rawNode;
c.remove(r);
c.add(r);
return this;
},_moveToBack:function(){
var c=this.parent.rawNode.children,r=this.rawNode;
c.remove(r);
c.insert(0,r);
return this;
},_getAdjustedMatrix:function(){
return this.matrix;
}});
dojo.declare("dojox.gfx.Group",dojox.gfx.Shape,{constructor:function(){
dojox.gfx.silverlight.Container._init.call(this);
},setRawNode:function(_a){
this.rawNode=_a;
}});
dojox.gfx.Group.nodeType="Canvas";
dojo.declare("dojox.gfx.Rect",dojox.gfx.shape.Rect,{setShape:function(_b){
this.shape=dojox.gfx.makeParameters(this.shape,_b);
this.bbox=null;
var r=this.rawNode,n=this.shape;
r.width=n.width;
r.height=n.height;
r.radiusX=r.radiusY=n.r;
return this._applyTransform();
},_getAdjustedMatrix:function(){
var m=this.matrix,s=this.shape,d={dx:s.x,dy:s.y};
return new dojox.gfx.Matrix2D(m?[m,d]:d);
}});
dojox.gfx.Rect.nodeType="Rectangle";
dojo.declare("dojox.gfx.Ellipse",dojox.gfx.shape.Ellipse,{setShape:function(_c){
this.shape=dojox.gfx.makeParameters(this.shape,_c);
this.bbox=null;
var r=this.rawNode,n=this.shape;
r.width=2*n.rx;
r.height=2*n.ry;
return this._applyTransform();
},_getAdjustedMatrix:function(){
var m=this.matrix,s=this.shape,d={dx:s.cx-s.rx,dy:s.cy-s.ry};
return new dojox.gfx.Matrix2D(m?[m,d]:d);
}});
dojox.gfx.Ellipse.nodeType="Ellipse";
dojo.declare("dojox.gfx.Circle",dojox.gfx.shape.Circle,{setShape:function(_d){
this.shape=dojox.gfx.makeParameters(this.shape,_d);
this.bbox=null;
var r=this.rawNode,n=this.shape;
r.width=r.height=2*n.r;
return this._applyTransform();
},_getAdjustedMatrix:function(){
var m=this.matrix,s=this.shape,d={dx:s.cx-s.r,dy:s.cy-s.r};
return new dojox.gfx.Matrix2D(m?[m,d]:d);
}});
dojox.gfx.Circle.nodeType="Ellipse";
dojo.declare("dojox.gfx.Line",dojox.gfx.shape.Line,{setShape:function(_e){
this.shape=dojox.gfx.makeParameters(this.shape,_e);
this.bbox=null;
var r=this.rawNode,n=this.shape;
r.x1=n.x1;
r.y1=n.y1;
r.x2=n.x2;
r.y2=n.y2;
return this;
}});
dojox.gfx.Line.nodeType="Line";
dojo.declare("dojox.gfx.Polyline",dojox.gfx.shape.Polyline,{setShape:function(_f,_10){
if(_f&&_f instanceof Array){
this.shape=dojox.gfx.makeParameters(this.shape,{points:_f});
if(_10&&this.shape.points.length){
this.shape.points.push(this.shape.points[0]);
}
}else{
this.shape=dojox.gfx.makeParameters(this.shape,_f);
}
this.bbox=null;
this._normalizePoints();
var p=this.shape.points,rp=[];
for(var i=0;i<p.length;++i){
rp.push(p[i].x,p[i].y);
}
this.rawNode.points=rp.join(",");
return this;
}});
dojox.gfx.Polyline.nodeType="Polyline";
dojo.declare("dojox.gfx.Image",dojox.gfx.shape.Image,{setShape:function(_11){
this.shape=dojox.gfx.makeParameters(this.shape,_11);
this.bbox=null;
var r=this.rawNode,n=this.shape;
r.width=n.width;
r.height=n.height;
r.source=n.src;
return this._applyTransform();
},_getAdjustedMatrix:function(){
var m=this.matrix,s=this.shape,d={dx:s.x,dy:s.y};
return new dojox.gfx.Matrix2D(m?[m,d]:d);
},setRawNode:function(_12){
this.rawNode=_12;
}});
dojox.gfx.Image.nodeType="Image";
dojo.declare("dojox.gfx.Text",dojox.gfx.shape.Text,{setShape:function(_13){
this.shape=dojox.gfx.makeParameters(this.shape,_13);
this.bbox=null;
var r=this.rawNode,s=this.shape;
r.text=s.text;
r.textDecorations=s.decoration==="underline"?"Underline":"None";
r["Canvas.Left"]=-10000;
r["Canvas.Top"]=-10000;
if(!this._delay){
this._delay=window.setTimeout(dojo.hitch(this,"_delayAlignment"),10);
}
return this;
},_delayAlignment:function(){
var r=this.rawNode,s=this.shape,w=r.actualWidth,h=r.actualHeight,x=s.x,y=s.y-h*0.75;
switch(s.align){
case "middle":
x-=w/2;
break;
case "end":
x-=w;
break;
}
this._delta={dx:x,dy:y};
r["Canvas.Left"]=0;
r["Canvas.Top"]=0;
this._applyTransform();
delete this._delay;
},_getAdjustedMatrix:function(){
var m=this.matrix,d=this._delta,x;
if(m){
x=d?[m,d]:m;
}else{
x=d?d:{};
}
return new dojox.gfx.Matrix2D(x);
},setStroke:function(){
return this;
},_setFillAttr:function(f){
this.rawNode.foreground=f;
},setRawNode:function(_14){
this.rawNode=_14;
},getTextWidth:function(){
return this.rawNode.actualWidth;
}});
dojox.gfx.Text.nodeType="TextBlock";
dojo.declare("dojox.gfx.Path",dojox.gfx.path.Path,{_updateWithSegment:function(_15){
dojox.gfx.Path.superclass._updateWithSegment.apply(this,arguments);
var p=this.shape.path;
if(typeof (p)=="string"){
this.rawNode.data=p?p:null;
}
},setShape:function(_16){
dojox.gfx.Path.superclass.setShape.apply(this,arguments);
var p=this.shape.path;
this.rawNode.data=p?p:null;
return this;
}});
dojox.gfx.Path.nodeType="Path";
dojo.declare("dojox.gfx.TextPath",dojox.gfx.path.TextPath,{_updateWithSegment:function(_17){
},setShape:function(_18){
},_setText:function(){
}});
dojox.gfx.TextPath.nodeType="text";
dojox.gfx.silverlight.surfaces={};
dojox.gfx.silverlight.nullFunc=function(){
};
dojo.declare("dojox.gfx.Surface",dojox.gfx.shape.Surface,{constructor:function(){
dojox.gfx.silverlight.Container._init.call(this);
},destroy:function(){
window[this._onLoadName]=dojox.gfx.silverlight.nullFunc;
delete dojox.gfx.silverlight.surfaces[this.rawNode.name];
this.inherited(arguments);
},setDimensions:function(_19,_1a){
this.width=dojox.gfx.normalizedLength(_19);
this.height=dojox.gfx.normalizedLength(_1a);
var p=this.rawNode&&this.rawNode.getHost();
if(p){
p.width=_19;
p.height=_1a;
}
return this;
},getDimensions:function(){
var p=this.rawNode&&this.rawNode.getHost();
var t=p?{width:p.content.actualWidth,height:p.content.actualHeight}:null;
if(t.width<=0){
t.width=this.width;
}
if(t.height<=0){
t.height=this.height;
}
return t;
}});
dojox.gfx.createSurface=function(_1b,_1c,_1d){
if(!_1c&&!_1d){
var pos=d.position(_1b);
_1c=_1c||pos.w;
_1d=_1d||pos.h;
}
if(typeof _1c=="number"){
_1c=_1c+"px";
}
if(typeof _1d=="number"){
_1d=_1d+"px";
}
var s=new dojox.gfx.Surface();
_1b=dojo.byId(_1b);
s._parent=_1b;
var t=_1b.ownerDocument.createElement("script");
t.type="text/xaml";
t.id=dojox.gfx._base._getUniqueId();
t.text="<?xml version='1.0'?><Canvas xmlns='http://schemas.microsoft.com/client/2007' Name='"+dojox.gfx._base._getUniqueId()+"'/>";
_1b.parentNode.insertBefore(t,_1b);
s._nodes.push(t);
var obj,_1e=dojox.gfx._base._getUniqueId(),_1f="__"+dojox.gfx._base._getUniqueId()+"_onLoad";
s._onLoadName=_1f;
window[_1f]=function(_20){
if(!s.rawNode){
s.rawNode=dojo.byId(_1e).content.root;
dojox.gfx.silverlight.surfaces[s.rawNode.name]=_1b;
s.onLoad(s);
}
};
if(dojo.isSafari){
obj="<embed type='application/x-silverlight' id='"+_1e+"' width='"+_1c+"' height='"+_1d+" background='transparent'"+" source='#"+t.id+"'"+" windowless='true'"+" maxFramerate='60'"+" onLoad='"+_1f+"'"+" onError='__dojoSilverlightError'"+" /><iframe style='visibility:hidden;height:0;width:0'/>";
}else{
obj="<object type='application/x-silverlight' data='data:application/x-silverlight,' id='"+_1e+"' width='"+_1c+"' height='"+_1d+"'>"+"<param name='background' value='transparent' />"+"<param name='source' value='#"+t.id+"' />"+"<param name='windowless' value='true' />"+"<param name='maxFramerate' value='60' />"+"<param name='onLoad' value='"+_1f+"' />"+"<param name='onError' value='__dojoSilverlightError' />"+"</object>";
}
_1b.innerHTML=obj;
var _21=dojo.byId(_1e);
if(_21.content&&_21.content.root){
s.rawNode=_21.content.root;
dojox.gfx.silverlight.surfaces[s.rawNode.name]=_1b;
}else{
s.rawNode=null;
s.isLoaded=false;
}
s._nodes.push(_21);
s.width=dojox.gfx.normalizedLength(_1c);
s.height=dojox.gfx.normalizedLength(_1d);
return s;
};
__dojoSilverlightError=function(_22,err){
var t="Silverlight Error:\n"+"Code: "+err.ErrorCode+"\n"+"Type: "+err.ErrorType+"\n"+"Message: "+err.ErrorMessage+"\n";
switch(err.ErrorType){
case "ParserError":
t+="XamlFile: "+err.xamlFile+"\n"+"Line: "+err.lineNumber+"\n"+"Position: "+err.charPosition+"\n";
break;
case "RuntimeError":
t+="MethodName: "+err.methodName+"\n";
if(err.lineNumber!=0){
t+="Line: "+err.lineNumber+"\n"+"Position: "+err.charPosition+"\n";
}
break;
}
console.error(t);
};
dojox.gfx.silverlight.Font={_setFont:function(){
var f=this.fontStyle,r=this.rawNode,fw=dojox.gfx.silverlight.fontweight,fo=dojox.gfx.silverlight.fonts,t=f.family.toLowerCase();
r.fontStyle=f.style=="italic"?"Italic":"Normal";
r.fontWeight=f.weight in fw?fw[f.weight]:f.weight;
r.fontSize=dojox.gfx.normalizedLength(f.size);
r.fontFamily=t in fo?fo[t]:f.family;
if(!this._delay){
this._delay=window.setTimeout(dojo.hitch(this,"_delayAlignment"),10);
}
}};
dojox.gfx.silverlight.Container={_init:function(){
dojox.gfx.shape.Container._init.call(this);
},add:function(_23){
if(this!=_23.getParent()){
dojox.gfx.shape.Container.add.apply(this,arguments);
this.rawNode.children.add(_23.rawNode);
}
return this;
},remove:function(_24,_25){
if(this==_24.getParent()){
var _26=_24.rawNode.getParent();
if(_26){
_26.children.remove(_24.rawNode);
}
dojox.gfx.shape.Container.remove.apply(this,arguments);
}
return this;
},clear:function(){
this.rawNode.children.clear();
return dojox.gfx.shape.Container.clear.apply(this,arguments);
},_moveChildToFront:dojox.gfx.shape.Container._moveChildToFront,_moveChildToBack:dojox.gfx.shape.Container._moveChildToBack};
dojo.mixin(dojox.gfx.shape.Creator,{createObject:function(_27,_28){
if(!this.rawNode){
return null;
}
var _29=new _27();
var _2a=this.rawNode.getHost().content.createFromXaml("<"+_27.nodeType+"/>");
_29.setRawNode(_2a);
_29.setShape(_28);
this.add(_29);
return _29;
}});
dojo.extend(dojox.gfx.Text,dojox.gfx.silverlight.Font);
dojo.extend(dojox.gfx.Group,dojox.gfx.silverlight.Container);
dojo.extend(dojox.gfx.Group,dojox.gfx.shape.Creator);
dojo.extend(dojox.gfx.Surface,dojox.gfx.silverlight.Container);
dojo.extend(dojox.gfx.Surface,dojox.gfx.shape.Creator);
(function(){
var _2b=dojox.gfx.silverlight.surfaces;
var _2c=function(s,a){
var ev={target:s,currentTarget:s,preventDefault:function(){
},stopPropagation:function(){
}};
if(a){
try{
ev.ctrlKey=a.ctrl;
ev.shiftKey=a.shift;
var p=a.getPosition(null);
ev.x=ev.offsetX=ev.layerX=p.x;
ev.y=ev.offsetY=ev.layerY=p.y;
var _2d=_2b[s.getHost().content.root.name];
var t=dojo.position(_2d);
ev.clientX=t.x+p.x;
ev.clientY=t.y+p.y;
}
catch(e){
}
}
return ev;
};
var _2e=function(s,a){
var ev={keyCode:a.platformKeyCode,ctrlKey:a.ctrl,shiftKey:a.shift};
return ev;
};
var _2f={onclick:{name:"MouseLeftButtonUp",fix:_2c},onmouseenter:{name:"MouseEnter",fix:_2c},onmouseleave:{name:"MouseLeave",fix:_2c},onmouseover:{name:"MouseEnter",fix:_2c},onmouseout:{name:"MouseLeave",fix:_2c},onmousedown:{name:"MouseLeftButtonDown",fix:_2c},onmouseup:{name:"MouseLeftButtonUp",fix:_2c},onmousemove:{name:"MouseMove",fix:_2c},onkeydown:{name:"KeyDown",fix:_2e},onkeyup:{name:"KeyUp",fix:_2e}};
var _30={connect:function(_31,_32,_33){
var _34,n=_31 in _2f?_2f[_31]:{name:_31,fix:function(){
return {};
}};
if(arguments.length>2){
_34=this.getEventSource().addEventListener(n.name,function(s,a){
dojo.hitch(_32,_33)(n.fix(s,a));
});
}else{
_34=this.getEventSource().addEventListener(n.name,function(s,a){
_32(n.fix(s,a));
});
}
return {name:n.name,token:_34};
},disconnect:function(_35){
this.getEventSource().removeEventListener(_35.name,_35.token);
}};
dojo.extend(dojox.gfx.Shape,_30);
dojo.extend(dojox.gfx.Surface,_30);
dojox.gfx.equalSources=function(a,b){
return a&&b&&a.equals(b);
};
})();
}
