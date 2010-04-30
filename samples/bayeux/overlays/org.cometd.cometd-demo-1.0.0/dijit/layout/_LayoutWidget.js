/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dijit.layout._LayoutWidget"]){
dojo._hasResource["dijit.layout._LayoutWidget"]=true;
dojo.provide("dijit.layout._LayoutWidget");
dojo.require("dijit._Widget");
dojo.require("dijit._Container");
dojo.require("dijit._Contained");
dojo.declare("dijit.layout._LayoutWidget",[dijit._Widget,dijit._Container,dijit._Contained],{baseClass:"dijitLayoutContainer",isLayoutContainer:true,postCreate:function(){
dojo.addClass(this.domNode,"dijitContainer");
dojo.addClass(this.domNode,this.baseClass);
},startup:function(){
if(this._started){
return;
}
dojo.forEach(this.getChildren(),function(_1){
_1.startup();
});
if(!this.getParent||!this.getParent()){
this.resize();
this._viewport=dijit.getViewport();
this.connect(dojo.global,"onresize",function(){
var _2=dijit.getViewport();
if(_2.w!=this._viewport.w||_2.h!=this._viewport.h){
this._viewport=_2;
this.resize();
}
});
}
this.inherited(arguments);
},resize:function(_3,_4){
var _5=this.domNode;
if(_3){
dojo.marginBox(_5,_3);
if(_3.t){
_5.style.top=_3.t+"px";
}
if(_3.l){
_5.style.left=_3.l+"px";
}
}
var mb=_4||{};
dojo.mixin(mb,_3||{});
if(!("h" in mb)||!("w" in mb)){
mb=dojo.mixin(dojo.marginBox(_5),mb);
}
var cs=dojo.getComputedStyle(_5);
var me=dojo._getMarginExtents(_5,cs);
var be=dojo._getBorderExtents(_5,cs);
var bb=(this._borderBox={w:mb.w-(me.w+be.w),h:mb.h-(me.h+be.h)});
var pe=dojo._getPadExtents(_5,cs);
this._contentBox={l:dojo._toPixelValue(_5,cs.paddingLeft),t:dojo._toPixelValue(_5,cs.paddingTop),w:bb.w-pe.w,h:bb.h-pe.h};
this.layout();
},layout:function(){
},_setupChild:function(_c){
dojo.addClass(_c.domNode,this.baseClass+"-child");
if(_c.baseClass){
dojo.addClass(_c.domNode,this.baseClass+"-"+_c.baseClass);
}
},addChild:function(_d,_e){
this.inherited(arguments);
if(this._started){
this._setupChild(_d);
}
},removeChild:function(_f){
dojo.removeClass(_f.domNode,this.baseClass+"-child");
if(_f.baseClass){
dojo.removeClass(_f.domNode,this.baseClass+"-"+_f.baseClass);
}
this.inherited(arguments);
}});
dijit.layout.marginBox2contentBox=function(_10,mb){
var cs=dojo.getComputedStyle(_10);
var me=dojo._getMarginExtents(_10,cs);
var pb=dojo._getPadBorderExtents(_10,cs);
return {l:dojo._toPixelValue(_10,cs.paddingLeft),t:dojo._toPixelValue(_10,cs.paddingTop),w:mb.w-(me.w+pb.w),h:mb.h-(me.h+pb.h)};
};
(function(){
var _15=function(_16){
return _16.substring(0,1).toUpperCase()+_16.substring(1);
};
var _17=function(_18,dim){
_18.resize?_18.resize(dim):dojo.marginBox(_18.domNode,dim);
dojo.mixin(_18,dojo.marginBox(_18.domNode));
dojo.mixin(_18,dim);
};
dijit.layout.layoutChildren=function(_1a,dim,_1c){
dim=dojo.mixin({},dim);
dojo.addClass(_1a,"dijitLayoutContainer");
_1c=dojo.filter(_1c,function(_1d){
return _1d.layoutAlign!="client";
}).concat(dojo.filter(_1c,function(_1e){
return _1e.layoutAlign=="client";
}));
dojo.forEach(_1c,function(_1f){
var elm=_1f.domNode,pos=_1f.layoutAlign;
var _22=elm.style;
_22.left=dim.l+"px";
_22.top=dim.t+"px";
_22.bottom=_22.right="auto";
dojo.addClass(elm,"dijitAlign"+_15(pos));
if(pos=="top"||pos=="bottom"){
_17(_1f,{w:dim.w});
dim.h-=_1f.h;
if(pos=="top"){
dim.t+=_1f.h;
}else{
_22.top=dim.t+dim.h+"px";
}
}else{
if(pos=="left"||pos=="right"){
_17(_1f,{h:dim.h});
dim.w-=_1f.w;
if(pos=="left"){
dim.l+=_1f.w;
}else{
_22.left=dim.l+dim.w+"px";
}
}else{
if(pos=="client"){
_17(_1f,dim);
}
}
}
});
};
})();
}
