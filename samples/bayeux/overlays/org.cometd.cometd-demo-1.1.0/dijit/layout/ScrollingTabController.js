/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dijit.layout.ScrollingTabController"]){
dojo._hasResource["dijit.layout.ScrollingTabController"]=true;
dojo.provide("dijit.layout.ScrollingTabController");
dojo.require("dijit.layout.TabController");
dojo.require("dijit.Menu");
dojo.declare("dijit.layout.ScrollingTabController",dijit.layout.TabController,{templateString:dojo.cache("dijit.layout","templates/ScrollingTabController.html","<div class=\"dijitTabListContainer-${tabPosition}\" style=\"visibility:hidden\">\n\t<div dojoType=\"dijit.layout._ScrollingTabControllerButton\" buttonType=\"menuBtn\" buttonClass=\"tabStripMenuButton\"\n\t\t\ttabPosition=\"${tabPosition}\" dojoAttachPoint=\"_menuBtn\" showLabel=false>&darr;</div>\n\t<div dojoType=\"dijit.layout._ScrollingTabControllerButton\" buttonType=\"leftBtn\" buttonClass=\"tabStripSlideButtonLeft\"\n\t\t\ttabPosition=\"${tabPosition}\" dojoAttachPoint=\"_leftBtn\" dojoAttachEvent=\"onClick: doSlideLeft\" showLabel=false>&larr;</div>\n\t<div dojoType=\"dijit.layout._ScrollingTabControllerButton\" buttonType=\"rightBtn\" buttonClass=\"tabStripSlideButtonRight\"\n\t\t\ttabPosition=\"${tabPosition}\" dojoAttachPoint=\"_rightBtn\" dojoAttachEvent=\"onClick: doSlideRight\" showLabel=false>&rarr;</div>\n\t<div class='dijitTabListWrapper' dojoAttachPoint='tablistWrapper'>\n\t\t<div wairole='tablist' dojoAttachEvent='onkeypress:onkeypress'\n\t\t\t\tdojoAttachPoint='containerNode' class='nowrapTabStrip'>\n\t\t</div>\n\t</div>\n</div>\n"),useMenu:true,useSlider:true,tabStripClass:"",widgetsInTemplate:true,_minScroll:5,attributeMap:dojo.delegate(dijit._Widget.prototype.attributeMap,{"class":"containerNode"}),postCreate:function(){
this.inherited(arguments);
var n=this.domNode;
this.scrollNode=this.tablistWrapper;
this._initButtons();
if(!this.tabStripClass){
this.tabStripClass="dijitTabContainer"+this.tabPosition.charAt(0).toUpperCase()+this.tabPosition.substr(1).replace(/-.*/,"")+"None";
dojo.addClass(n,"tabStrip-disabled");
}
dojo.addClass(this.tablistWrapper,this.tabStripClass);
},onStartup:function(){
this.inherited(arguments);
dojo.style(this.domNode,"visibility","visible");
this._postStartup=true;
},onAddChild:function(_1,_2){
this.inherited(arguments);
var _3;
if(this.useMenu){
_3=new dijit.MenuItem({label:_1.title,onClick:dojo.hitch(this,function(){
this.onSelectChild(_1);
})});
this._menuChildren[_1.id]=_3;
this._menu.addChild(_3,_2);
}
this.pane2handles[_1.id].push(this.connect(this.pane2button[_1.id],"attr",function(_4,_5){
if(this._postStartup){
if(arguments.length==2&&_4=="label"){
if(_3){
_3.attr(_4,_5);
}
if(this._dim){
this.resize(this._dim);
}
}
}
}));
dojo.style(this.containerNode,"width",(dojo.style(this.containerNode,"width")+200)+"px");
},onRemoveChild:function(_6,_7){
var _8=this.pane2button[_6.id];
if(this._selectedTab===_8.domNode){
this._selectedTab=null;
}
if(this.useMenu&&_6&&_6.id&&this._menuChildren[_6.id]){
this._menu.removeChild(this._menuChildren[_6.id]);
this._menuChildren[_6.id].destroy();
delete this._menuChildren[_6.id];
}
this.inherited(arguments);
},_initButtons:function(){
this._menuChildren={};
this._btnWidth=0;
this._buttons=dojo.query("> .tabStripButton",this.domNode).filter(function(_9){
if((this.useMenu&&_9==this._menuBtn.domNode)||(this.useSlider&&(_9==this._rightBtn.domNode||_9==this._leftBtn.domNode))){
this._btnWidth+=dojo.marginBox(_9).w;
return true;
}else{
dojo.style(_9,"display","none");
return false;
}
},this);
if(this.useMenu){
this._menu=new dijit.Menu({id:this.id+"_menu",targetNodeIds:[this._menuBtn.domNode],leftClickToOpen:true,refocus:false});
this._supportingWidgets.push(this._menu);
}
},_getTabsWidth:function(){
var _a=this.getChildren();
if(_a.length){
var _b=_a[this.isLeftToRight()?0:_a.length-1].domNode,_c=_a[this.isLeftToRight()?_a.length-1:0].domNode;
return _c.offsetLeft+dojo.style(_c,"width")-_b.offsetLeft;
}else{
return 0;
}
},_enableBtn:function(_d){
var _e=this._getTabsWidth();
_d=_d||dojo.style(this.scrollNode,"width");
return _e>0&&_d<_e;
},resize:function(_f){
if(this.domNode.offsetWidth==0){
return;
}
this._dim=_f;
this.scrollNode.style.height="auto";
this._contentBox=dijit.layout.marginBox2contentBox(this.domNode,{h:0,w:_f.w});
this._contentBox.h=this.scrollNode.offsetHeight;
dojo.contentBox(this.domNode,this._contentBox);
var _10=this._enableBtn(this._contentBox.w);
this._buttons.style("display",_10?"":"none");
this._leftBtn.layoutAlign="left";
this._rightBtn.layoutAlign="right";
this._menuBtn.layoutAlign=this.isLeftToRight()?"right":"left";
dijit.layout.layoutChildren(this.domNode,this._contentBox,[this._menuBtn,this._leftBtn,this._rightBtn,{domNode:this.scrollNode,layoutAlign:"client"}]);
if(this._selectedTab){
var w=this.scrollNode,sl=this._convertToScrollLeft(this._getScrollForSelectedTab());
w.scrollLeft=sl;
}
this._setButtonClass(this._getScroll());
},_getScroll:function(){
var sl=(this.isLeftToRight()||dojo.isIE<8||dojo.isQuirks||dojo.isWebKit)?this.scrollNode.scrollLeft:dojo.style(this.containerNode,"width")-dojo.style(this.scrollNode,"width")+(dojo.isIE==8?-1:1)*this.scrollNode.scrollLeft;
return sl;
},_convertToScrollLeft:function(val){
if(this.isLeftToRight()||dojo.isIE<8||dojo.isQuirks||dojo.isWebKit){
return val;
}else{
var _11=dojo.style(this.containerNode,"width")-dojo.style(this.scrollNode,"width");
return (dojo.isIE==8?-1:1)*(val-_11);
}
},onSelectChild:function(_12){
var tab=this.pane2button[_12.id];
if(!tab||!_12){
return;
}
var _13=tab.domNode;
if(_13!=this._selectedTab){
this._selectedTab=_13;
var sl=this._getScroll();
if(sl>_13.offsetLeft||sl+dojo.style(this.scrollNode,"width")<_13.offsetLeft+dojo.style(_13,"width")){
this.createSmoothScroll().play();
}
}
this.inherited(arguments);
},_getScrollBounds:function(){
var _14=this.getChildren(),_15=dojo.style(this.scrollNode,"width"),_16=dojo.style(this.containerNode,"width"),_17=_16-_15,_18=this._getTabsWidth();
if(_14.length&&_18>_15){
return {min:this.isLeftToRight()?0:_14[_14.length-1].domNode.offsetLeft,max:this.isLeftToRight()?(_14[_14.length-1].domNode.offsetLeft+dojo.style(_14[_14.length-1].domNode,"width"))-_15:_17};
}else{
var _19=this.isLeftToRight()?0:_17;
return {min:_19,max:_19};
}
},_getScrollForSelectedTab:function(){
var w=this.scrollNode,n=this._selectedTab,_1a=dojo.style(this.scrollNode,"width"),_1b=this._getScrollBounds();
var pos=(n.offsetLeft+dojo.style(n,"width")/2)-_1a/2;
pos=Math.min(Math.max(pos,_1b.min),_1b.max);
return pos;
},createSmoothScroll:function(x){
if(arguments.length>0){
var _1c=this._getScrollBounds();
x=Math.min(Math.max(x,_1c.min),_1c.max);
}else{
x=this._getScrollForSelectedTab();
}
if(this._anim&&this._anim.status()=="playing"){
this._anim.stop();
}
var _1d=this,w=this.scrollNode,_1e=new dojo._Animation({beforeBegin:function(){
if(this.curve){
delete this.curve;
}
var _1f=w.scrollLeft,_20=_1d._convertToScrollLeft(x);
_1e.curve=new dojo._Line(_1f,_20);
},onAnimate:function(val){
w.scrollLeft=val;
}});
this._anim=_1e;
this._setButtonClass(x);
return _1e;
},_getBtnNode:function(e){
var n=e.target;
while(n&&!dojo.hasClass(n,"tabStripButton")){
n=n.parentNode;
}
return n;
},doSlideRight:function(e){
this.doSlide(1,this._getBtnNode(e));
},doSlideLeft:function(e){
this.doSlide(-1,this._getBtnNode(e));
},doSlide:function(_21,_22){
if(_22&&dojo.hasClass(_22,"dijitTabBtnDisabled")){
return;
}
var _23=dojo.style(this.scrollNode,"width");
var d=(_23*0.75)*_21;
var to=this._getScroll()+d;
this._setButtonClass(to);
this.createSmoothScroll(to).play();
},_setButtonClass:function(_24){
var cls="dijitTabBtnDisabled",_25=this._getScrollBounds();
dojo.toggleClass(this._leftBtn.domNode,cls,_24<=_25.min);
dojo.toggleClass(this._rightBtn.domNode,cls,_24>=_25.max);
}});
dojo.declare("dijit.layout._ScrollingTabControllerButton",dijit.form.Button,{baseClass:"dijitTab",buttonType:"",buttonClass:"",tabPosition:"top",templateString:dojo.cache("dijit.layout","templates/_ScrollingTabControllerButton.html","<div id=\"${id}-${buttonType}\" class=\"tabStripButton dijitTab ${buttonClass} tabStripButton-${tabPosition}\"\n\t\tdojoAttachEvent=\"onclick:_onButtonClick,onmouseenter:_onMouse,onmouseleave:_onMouse,onmousedown:_onMouse\">\n\t<div role=\"presentation\" wairole=\"presentation\" class=\"dijitTabInnerDiv\" dojoattachpoint=\"innerDiv,focusNode\">\n\t\t<div role=\"presentation\" wairole=\"presentation\" class=\"dijitTabContent dijitButtonContents\" dojoattachpoint=\"tabContent\">\n\t\t\t<img src=\"${_blankGif}\"/>\n\t\t\t<span dojoAttachPoint=\"containerNode,titleNode\" class=\"dijitButtonText\"></span>\n\t\t</div>\n\t</div>\n</div>\n"),tabIndex:""});
}
