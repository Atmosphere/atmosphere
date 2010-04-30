/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dijit.layout.AccordionContainer"]){
dojo._hasResource["dijit.layout.AccordionContainer"]=true;
dojo.provide("dijit.layout.AccordionContainer");
dojo.require("dojo.fx");
dojo.require("dijit._Container");
dojo.require("dijit._Templated");
dojo.require("dijit.layout.StackContainer");
dojo.require("dijit.layout.ContentPane");
dojo.require("dijit.layout.AccordionPane");
dojo.declare("dijit.layout.AccordionContainer",dijit.layout.StackContainer,{duration:dijit.defaultDuration,buttonWidget:"dijit.layout._AccordionButton",_verticalSpace:0,baseClass:"dijitAccordionContainer",postCreate:function(){
this.domNode.style.overflow="hidden";
this.inherited(arguments);
dijit.setWaiRole(this.domNode,"tablist");
},startup:function(){
if(this._started){
return;
}
this.inherited(arguments);
if(this.selectedChildWidget){
var _1=this.selectedChildWidget.containerNode.style;
_1.display="";
_1.overflow="auto";
this.selectedChildWidget._buttonWidget._setSelectedState(true);
}
},_getTargetHeight:function(_2){
var cs=dojo.getComputedStyle(_2);
return Math.max(this._verticalSpace-dojo._getPadBorderExtents(_2,cs).h,0);
},layout:function(){
var _3=this.selectedChildWidget;
var _4=0;
dojo.forEach(this.getChildren(),function(_5){
_4+=_5._buttonWidget.getTitleHeight();
});
var _6=this._contentBox;
this._verticalSpace=_6.h-_4;
this._containerContentBox={h:this._verticalSpace,w:_6.w};
if(_3){
_3.resize(this._containerContentBox);
}
},_setupChild:function(_7){
var _8=dojo.getObject(this.buttonWidget);
var _9=(_7._buttonWidget=new _8({contentWidget:_7,label:_7.title,title:_7.tooltip,iconClass:_7.iconClass,id:_7.id+"_button",parent:this}));
_7._accordionConnectHandle=this.connect(_7,"attr",function(_a,_b){
if(arguments.length==2){
switch(_a){
case "title":
case "iconClass":
_9.attr(_a,_b);
}
}
});
dojo.place(_7._buttonWidget.domNode,_7.domNode,"before");
this.inherited(arguments);
},removeChild:function(_c){
this.disconnect(_c._accordionConnectHandle);
delete _c._accordionConnectHandle;
_c._buttonWidget.destroy();
delete _c._buttonWidget;
this.inherited(arguments);
},getChildren:function(){
return dojo.filter(this.inherited(arguments),function(_d){
return _d.declaredClass!=this.buttonWidget;
},this);
},destroy:function(){
dojo.forEach(this.getChildren(),function(_e){
_e._buttonWidget.destroy();
});
this.inherited(arguments);
},_transition:function(_f,_10){
if(this._inTransition){
return;
}
this._inTransition=true;
var _11=[];
var _12=this._verticalSpace;
if(_f){
_f._buttonWidget.setSelected(true);
this._showChild(_f);
if(this.doLayout&&_f.resize){
_f.resize(this._containerContentBox);
}
var _13=_f.domNode;
dojo.addClass(_13,"dijitVisible");
dojo.removeClass(_13,"dijitHidden");
var _14=_13.style.overflow;
_13.style.overflow="hidden";
_11.push(dojo.animateProperty({node:_13,duration:this.duration,properties:{height:{start:1,end:this._getTargetHeight(_13)}},onEnd:dojo.hitch(this,function(){
_13.style.overflow=_14;
delete this._inTransition;
})}));
}
if(_10){
_10._buttonWidget.setSelected(false);
var _15=_10.domNode,_16=_15.style.overflow;
_15.style.overflow="hidden";
_11.push(dojo.animateProperty({node:_15,duration:this.duration,properties:{height:{start:this._getTargetHeight(_15),end:1}},onEnd:function(){
dojo.addClass(_15,"dijitHidden");
dojo.removeClass(_15,"dijitVisible");
_15.style.overflow=_16;
if(_10.onHide){
_10.onHide();
}
}}));
}
dojo.fx.combine(_11).play();
},_onKeyPress:function(e,_17){
if(this._inTransition||this.disabled||e.altKey||!(_17||e.ctrlKey)){
if(this._inTransition){
dojo.stopEvent(e);
}
return;
}
var k=dojo.keys,c=e.charOrCode;
if((_17&&(c==k.LEFT_ARROW||c==k.UP_ARROW))||(e.ctrlKey&&c==k.PAGE_UP)){
this._adjacent(false)._buttonWidget._onTitleClick();
dojo.stopEvent(e);
}else{
if((_17&&(c==k.RIGHT_ARROW||c==k.DOWN_ARROW))||(e.ctrlKey&&(c==k.PAGE_DOWN||c==k.TAB))){
this._adjacent(true)._buttonWidget._onTitleClick();
dojo.stopEvent(e);
}
}
}});
dojo.declare("dijit.layout._AccordionButton",[dijit._Widget,dijit._Templated],{templateString:dojo.cache("dijit.layout","templates/AccordionButton.html","<div dojoAttachPoint='titleNode,focusNode' dojoAttachEvent='ondijitclick:_onTitleClick,onkeypress:_onTitleKeyPress,onfocus:_handleFocus,onblur:_handleFocus,onmouseenter:_onTitleEnter,onmouseleave:_onTitleLeave'\n\t\tclass='dijitAccordionTitle' wairole=\"tab\" waiState=\"expanded-false\"\n\t\t><span class='dijitInline dijitAccordionArrow' waiRole=\"presentation\"></span\n\t\t><span class='arrowTextUp' waiRole=\"presentation\">+</span\n\t\t><span class='arrowTextDown' waiRole=\"presentation\">-</span\n\t\t><img src=\"${_blankGif}\" alt=\"\" dojoAttachPoint='iconNode' style=\"vertical-align: middle\" waiRole=\"presentation\"/>\n\t\t<span waiRole=\"presentation\" dojoAttachPoint='titleTextNode' class='dijitAccordionText'></span>\n</div>\n"),attributeMap:dojo.mixin(dojo.clone(dijit.layout.ContentPane.prototype.attributeMap),{label:{node:"titleTextNode",type:"innerHTML"},title:{node:"titleTextNode",type:"attribute",attribute:"title"},iconClass:{node:"iconNode",type:"class"}}),baseClass:"dijitAccordionTitle",getParent:function(){
return this.parent;
},postCreate:function(){
this.inherited(arguments);
dojo.setSelectable(this.domNode,false);
this.setSelected(this.selected);
var _18=dojo.attr(this.domNode,"id").replace(" ","_");
dojo.attr(this.titleTextNode,"id",_18+"_title");
dijit.setWaiState(this.focusNode,"labelledby",dojo.attr(this.titleTextNode,"id"));
},getTitleHeight:function(){
return dojo.marginBox(this.titleNode).h;
},_onTitleClick:function(){
var _19=this.getParent();
if(!_19._inTransition){
_19.selectChild(this.contentWidget);
dijit.focus(this.focusNode);
}
},_onTitleEnter:function(){
dojo.addClass(this.focusNode,"dijitAccordionTitle-hover");
},_onTitleLeave:function(){
dojo.removeClass(this.focusNode,"dijitAccordionTitle-hover");
},_onTitleKeyPress:function(evt){
return this.getParent()._onKeyPress(evt,this.contentWidget);
},_setSelectedState:function(_1a){
this.selected=_1a;
dojo[(_1a?"addClass":"removeClass")](this.titleNode,"dijitAccordionTitle-selected");
dijit.setWaiState(this.focusNode,"expanded",_1a);
dijit.setWaiState(this.focusNode,"selected",_1a);
this.focusNode.setAttribute("tabIndex",_1a?"0":"-1");
},_handleFocus:function(e){
dojo.toggleClass(this.titleTextNode,"dijitAccordionFocused",e.type=="focus");
},setSelected:function(_1b){
this._setSelectedState(_1b);
if(_1b){
var cw=this.contentWidget;
if(cw.onSelected){
cw.onSelected();
}
}
}});
}
