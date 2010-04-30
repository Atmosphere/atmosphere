/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dijit.layout.BorderContainer"]){
dojo._hasResource["dijit.layout.BorderContainer"]=true;
dojo.provide("dijit.layout.BorderContainer");
dojo.require("dijit.layout._LayoutWidget");
dojo.require("dojo.cookie");
dojo.declare("dijit.layout.BorderContainer",dijit.layout._LayoutWidget,{design:"headline",gutters:true,liveSplitters:true,persist:false,baseClass:"dijitBorderContainer",_splitterClass:"dijit.layout._Splitter",postMixInProperties:function(){
if(!this.gutters){
this.baseClass+="NoGutter";
}
this.inherited(arguments);
},postCreate:function(){
this.inherited(arguments);
this._splitters={};
this._splitterThickness={};
},startup:function(){
if(this._started){
return;
}
dojo.forEach(this.getChildren(),this._setupChild,this);
this.inherited(arguments);
},_setupChild:function(_1){
var _2=_1.region;
if(_2){
this.inherited(arguments);
dojo.addClass(_1.domNode,this.baseClass+"Pane");
var _3=this.isLeftToRight();
if(_2=="leading"){
_2=_3?"left":"right";
}
if(_2=="trailing"){
_2=_3?"right":"left";
}
this["_"+_2]=_1.domNode;
this["_"+_2+"Widget"]=_1;
if((_1.splitter||this.gutters)&&!this._splitters[_2]){
var _4=dojo.getObject(_1.splitter?this._splitterClass:"dijit.layout._Gutter");
var _5=new _4({container:this,child:_1,region:_2,live:this.liveSplitters});
_5.isSplitter=true;
this._splitters[_2]=_5.domNode;
dojo.place(this._splitters[_2],_1.domNode,"after");
_5.startup();
}
_1.region=_2;
}
},_computeSplitterThickness:function(_6){
this._splitterThickness[_6]=this._splitterThickness[_6]||dojo.marginBox(this._splitters[_6])[(/top|bottom/.test(_6)?"h":"w")];
},layout:function(){
for(var _7 in this._splitters){
this._computeSplitterThickness(_7);
}
this._layoutChildren();
},addChild:function(_8,_9){
this.inherited(arguments);
if(this._started){
this.layout();
}
},removeChild:function(_a){
var _b=_a.region;
var _c=this._splitters[_b];
if(_c){
dijit.byNode(_c).destroy();
delete this._splitters[_b];
delete this._splitterThickness[_b];
}
this.inherited(arguments);
delete this["_"+_b];
delete this["_"+_b+"Widget"];
if(this._started){
this._layoutChildren(_a.region);
}
dojo.removeClass(_a.domNode,this.baseClass+"Pane");
},getChildren:function(){
return dojo.filter(this.inherited(arguments),function(_d){
return !_d.isSplitter;
});
},getSplitter:function(_e){
var _f=this._splitters[_e];
return _f?dijit.byNode(_f):null;
},resize:function(_10,_11){
if(!this.cs||!this.pe){
var _12=this.domNode;
this.cs=dojo.getComputedStyle(_12);
this.pe=dojo._getPadExtents(_12,this.cs);
this.pe.r=dojo._toPixelValue(_12,this.cs.paddingRight);
this.pe.b=dojo._toPixelValue(_12,this.cs.paddingBottom);
dojo.style(_12,"padding","0px");
}
this.inherited(arguments);
},_layoutChildren:function(_13){
if(!this._borderBox||!this._borderBox.h){
return;
}
var _14=(this.design=="sidebar");
var _15=0,_16=0,_17=0,_18=0;
var _19={},_1a={},_1b={},_1c={},_1d=(this._center&&this._center.style)||{};
var _1e=/left|right/.test(_13);
var _1f=!_13||(!_1e&&!_14);
var _20=!_13||(_1e&&_14);
if(this._top){
_19=_20&&this._top.style;
_15=dojo.marginBox(this._top).h;
}
if(this._left){
_1a=_1f&&this._left.style;
_17=dojo.marginBox(this._left).w;
}
if(this._right){
_1b=_1f&&this._right.style;
_18=dojo.marginBox(this._right).w;
}
if(this._bottom){
_1c=_20&&this._bottom.style;
_16=dojo.marginBox(this._bottom).h;
}
var _21=this._splitters;
var _22=_21.top,_23=_21.bottom,_24=_21.left,_25=_21.right;
var _26=this._splitterThickness;
var _27=_26.top||0,_28=_26.left||0,_29=_26.right||0,_2a=_26.bottom||0;
if(_28>50||_29>50){
setTimeout(dojo.hitch(this,function(){
this._splitterThickness={};
for(var _2b in this._splitters){
this._computeSplitterThickness(_2b);
}
this._layoutChildren();
}),50);
return false;
}
var pe=this.pe;
var _2c={left:(_14?_17+_28:0)+pe.l+"px",right:(_14?_18+_29:0)+pe.r+"px"};
if(_22){
dojo.mixin(_22.style,_2c);
_22.style.top=_15+pe.t+"px";
}
if(_23){
dojo.mixin(_23.style,_2c);
_23.style.bottom=_16+pe.b+"px";
}
_2c={top:(_14?0:_15+_27)+pe.t+"px",bottom:(_14?0:_16+_2a)+pe.b+"px"};
if(_24){
dojo.mixin(_24.style,_2c);
_24.style.left=_17+pe.l+"px";
}
if(_25){
dojo.mixin(_25.style,_2c);
_25.style.right=_18+pe.r+"px";
}
dojo.mixin(_1d,{top:pe.t+_15+_27+"px",left:pe.l+_17+_28+"px",right:pe.r+_18+_29+"px",bottom:pe.b+_16+_2a+"px"});
var _2d={top:_14?pe.t+"px":_1d.top,bottom:_14?pe.b+"px":_1d.bottom};
dojo.mixin(_1a,_2d);
dojo.mixin(_1b,_2d);
_1a.left=pe.l+"px";
_1b.right=pe.r+"px";
_19.top=pe.t+"px";
_1c.bottom=pe.b+"px";
if(_14){
_19.left=_1c.left=_17+_28+pe.l+"px";
_19.right=_1c.right=_18+_29+pe.r+"px";
}else{
_19.left=_1c.left=pe.l+"px";
_19.right=_1c.right=pe.r+"px";
}
var _2e=this._borderBox.h-pe.t-pe.b,_2f=_2e-(_15+_27+_16+_2a),_30=_14?_2e:_2f;
var _31=this._borderBox.w-pe.l-pe.r,_32=_31-(_17+_28+_18+_29),_33=_14?_32:_31;
var dim={top:{w:_33,h:_15},bottom:{w:_33,h:_16},left:{w:_17,h:_30},right:{w:_18,h:_30},center:{h:_2f,w:_32}};
var _34=dojo.isIE<8||(dojo.isIE&&dojo.isQuirks)||dojo.some(this.getChildren(),function(_35){
return _35.domNode.tagName=="TEXTAREA"||_35.domNode.tagName=="INPUT";
});
if(_34){
var _36=function(_37,_38,_39){
if(_37){
(_37.resize?_37.resize(_38,_39):dojo.marginBox(_37.domNode,_38));
}
};
if(_24){
_24.style.height=_30;
}
if(_25){
_25.style.height=_30;
}
_36(this._leftWidget,{h:_30},dim.left);
_36(this._rightWidget,{h:_30},dim.right);
if(_22){
_22.style.width=_33;
}
if(_23){
_23.style.width=_33;
}
_36(this._topWidget,{w:_33},dim.top);
_36(this._bottomWidget,{w:_33},dim.bottom);
_36(this._centerWidget,dim.center);
}else{
var _3a={};
if(_13){
_3a[_13]=_3a.center=true;
if(/top|bottom/.test(_13)&&this.design!="sidebar"){
_3a.left=_3a.right=true;
}else{
if(/left|right/.test(_13)&&this.design=="sidebar"){
_3a.top=_3a.bottom=true;
}
}
}
dojo.forEach(this.getChildren(),function(_3b){
if(_3b.resize&&(!_13||_3b.region in _3a)){
_3b.resize(null,dim[_3b.region]);
}
},this);
}
},destroy:function(){
for(var _3c in this._splitters){
var _3d=this._splitters[_3c];
dijit.byNode(_3d).destroy();
dojo.destroy(_3d);
}
delete this._splitters;
delete this._splitterThickness;
this.inherited(arguments);
}});
dojo.extend(dijit._Widget,{region:"",splitter:false,minSize:0,maxSize:Infinity});
dojo.require("dijit._Templated");
dojo.declare("dijit.layout._Splitter",[dijit._Widget,dijit._Templated],{live:true,templateString:"<div class=\"dijitSplitter\" dojoAttachEvent=\"onkeypress:_onKeyPress,onmousedown:_startDrag,onmouseenter:_onMouse,onmouseleave:_onMouse\" tabIndex=\"0\" waiRole=\"separator\"><div class=\"dijitSplitterThumb\"></div></div>",postCreate:function(){
this.inherited(arguments);
this.horizontal=/top|bottom/.test(this.region);
dojo.addClass(this.domNode,"dijitSplitter"+(this.horizontal?"H":"V"));
this._factor=/top|left/.test(this.region)?1:-1;
this._cookieName=this.container.id+"_"+this.region;
if(this.container.persist){
var _3e=dojo.cookie(this._cookieName);
if(_3e){
this.child.domNode.style[this.horizontal?"height":"width"]=_3e;
}
}
},_computeMaxSize:function(){
var dim=this.horizontal?"h":"w",_3f=this.container._splitterThickness[this.region];
var _40={left:"right",right:"left",top:"bottom",bottom:"top",leading:"trailing",trailing:"leading"},_41=this.container["_"+_40[this.region]];
var _42=dojo.contentBox(this.container.domNode)[dim]-(_41?dojo.marginBox(_41)[dim]:0)-20-_3f*2;
return Math.min(this.child.maxSize,_42);
},_startDrag:function(e){
if(!this.cover){
this.cover=dojo.doc.createElement("div");
dojo.addClass(this.cover,"dijitSplitterCover");
dojo.place(this.cover,this.child.domNode,"after");
}
dojo.addClass(this.cover,"dijitSplitterCoverActive");
if(this.fake){
dojo.destroy(this.fake);
}
if(!(this._resize=this.live)){
(this.fake=this.domNode.cloneNode(true)).removeAttribute("id");
dojo.addClass(this.domNode,"dijitSplitterShadow");
dojo.place(this.fake,this.domNode,"after");
}
dojo.addClass(this.domNode,"dijitSplitterActive");
dojo.addClass(this.domNode,"dijitSplitter"+(this.horizontal?"H":"V")+"Active");
if(this.fake){
dojo.removeClass(this.fake,"dijitSplitterHover");
dojo.removeClass(this.fake,"dijitSplitter"+(this.horizontal?"H":"V")+"Hover");
}
var _43=this._factor,max=this._computeMaxSize(),min=this.child.minSize||20,_44=this.horizontal,_45=_44?"pageY":"pageX",_46=e[_45],_47=this.domNode.style,dim=_44?"h":"w",_48=dojo.marginBox(this.child.domNode)[dim],_49=this.region,_4a=parseInt(this.domNode.style[_49],10),_4b=this._resize,mb={},_4c=this.child.domNode,_4d=dojo.hitch(this.container,this.container._layoutChildren),de=dojo.doc.body;
this._handlers=(this._handlers||[]).concat([dojo.connect(de,"onmousemove",this._drag=function(e,_4e){
var _4f=e[_45]-_46,_50=_43*_4f+_48,_51=Math.max(Math.min(_50,max),min);
if(_4b||_4e){
mb[dim]=_51;
dojo.marginBox(_4c,mb);
_4d(_49);
}
_47[_49]=_43*_4f+_4a+(_51-_50)+"px";
}),dojo.connect(dojo.doc,"ondragstart",dojo.stopEvent),dojo.connect(dojo.body(),"onselectstart",dojo.stopEvent),dojo.connect(de,"onmouseup",this,"_stopDrag")]);
dojo.stopEvent(e);
},_onMouse:function(e){
var o=(e.type=="mouseover"||e.type=="mouseenter");
dojo.toggleClass(this.domNode,"dijitSplitterHover",o);
dojo.toggleClass(this.domNode,"dijitSplitter"+(this.horizontal?"H":"V")+"Hover",o);
},_stopDrag:function(e){
try{
if(this.cover){
dojo.removeClass(this.cover,"dijitSplitterCoverActive");
}
if(this.fake){
dojo.destroy(this.fake);
}
dojo.removeClass(this.domNode,"dijitSplitterActive");
dojo.removeClass(this.domNode,"dijitSplitter"+(this.horizontal?"H":"V")+"Active");
dojo.removeClass(this.domNode,"dijitSplitterShadow");
this._drag(e);
this._drag(e,true);
}
finally{
this._cleanupHandlers();
delete this._drag;
}
if(this.container.persist){
dojo.cookie(this._cookieName,this.child.domNode.style[this.horizontal?"height":"width"],{expires:365});
}
},_cleanupHandlers:function(){
dojo.forEach(this._handlers,dojo.disconnect);
delete this._handlers;
},_onKeyPress:function(e){
this._resize=true;
var _52=this.horizontal;
var _53=1;
var dk=dojo.keys;
switch(e.charOrCode){
case _52?dk.UP_ARROW:dk.LEFT_ARROW:
_53*=-1;
case _52?dk.DOWN_ARROW:dk.RIGHT_ARROW:
break;
default:
return;
}
var _54=dojo.marginBox(this.child.domNode)[_52?"h":"w"]+this._factor*_53;
var mb={};
mb[this.horizontal?"h":"w"]=Math.max(Math.min(_54,this._computeMaxSize()),this.child.minSize);
dojo.marginBox(this.child.domNode,mb);
this.container._layoutChildren(this.region);
dojo.stopEvent(e);
},destroy:function(){
this._cleanupHandlers();
delete this.child;
delete this.container;
delete this.cover;
delete this.fake;
this.inherited(arguments);
}});
dojo.declare("dijit.layout._Gutter",[dijit._Widget,dijit._Templated],{templateString:"<div class=\"dijitGutter\" waiRole=\"presentation\"></div>",postCreate:function(){
this.horizontal=/top|bottom/.test(this.region);
dojo.addClass(this.domNode,"dijitGutter"+(this.horizontal?"H":"V"));
}});
}
