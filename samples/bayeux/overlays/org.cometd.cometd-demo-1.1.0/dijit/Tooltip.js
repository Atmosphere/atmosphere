/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dijit.Tooltip"]){
dojo._hasResource["dijit.Tooltip"]=true;
dojo.provide("dijit.Tooltip");
dojo.require("dijit._Widget");
dojo.require("dijit._Templated");
dojo.declare("dijit._MasterTooltip",[dijit._Widget,dijit._Templated],{duration:dijit.defaultDuration,templateString:dojo.cache("dijit","templates/Tooltip.html","<div class=\"dijitTooltip dijitTooltipLeft\" id=\"dojoTooltip\">\n\t<div class=\"dijitTooltipContainer dijitTooltipContents\" dojoAttachPoint=\"containerNode\" waiRole='alert'></div>\n\t<div class=\"dijitTooltipConnector\"></div>\n</div>\n"),postCreate:function(){
dojo.body().appendChild(this.domNode);
this.bgIframe=new dijit.BackgroundIframe(this.domNode);
this.fadeIn=dojo.fadeIn({node:this.domNode,duration:this.duration,onEnd:dojo.hitch(this,"_onShow")});
this.fadeOut=dojo.fadeOut({node:this.domNode,duration:this.duration,onEnd:dojo.hitch(this,"_onHide")});
},show:function(_1,_2,_3){
if(this.aroundNode&&this.aroundNode===_2){
return;
}
if(this.fadeOut.status()=="playing"){
this._onDeck=arguments;
return;
}
this.containerNode.innerHTML=_1;
this.domNode.style.top=(this.domNode.offsetTop+1)+"px";
var _4=dijit.placeOnScreenAroundElement(this.domNode,_2,dijit.getPopupAroundAlignment((_3&&_3.length)?_3:dijit.Tooltip.defaultPosition,this.isLeftToRight()),dojo.hitch(this,"orient"));
dojo.style(this.domNode,"opacity",0);
this.fadeIn.play();
this.isShowingNow=true;
this.aroundNode=_2;
},orient:function(_5,_6,_7){
_5.className="dijitTooltip "+{"BL-TL":"dijitTooltipBelow dijitTooltipABLeft","TL-BL":"dijitTooltipAbove dijitTooltipABLeft","BR-TR":"dijitTooltipBelow dijitTooltipABRight","TR-BR":"dijitTooltipAbove dijitTooltipABRight","BR-BL":"dijitTooltipRight","BL-BR":"dijitTooltipLeft"}[_6+"-"+_7];
},_onShow:function(){
if(dojo.isIE){
this.domNode.style.filter="";
}
},hide:function(_8){
if(this._onDeck&&this._onDeck[1]==_8){
this._onDeck=null;
}else{
if(this.aroundNode===_8){
this.fadeIn.stop();
this.isShowingNow=false;
this.aroundNode=null;
this.fadeOut.play();
}else{
}
}
},_onHide:function(){
this.domNode.style.cssText="";
if(this._onDeck){
this.show.apply(this,this._onDeck);
this._onDeck=null;
}
}});
dijit.showTooltip=function(_9,_a,_b){
if(!dijit._masterTT){
dijit._masterTT=new dijit._MasterTooltip();
}
return dijit._masterTT.show(_9,_a,_b);
};
dijit.hideTooltip=function(_c){
if(!dijit._masterTT){
dijit._masterTT=new dijit._MasterTooltip();
}
return dijit._masterTT.hide(_c);
};
dojo.declare("dijit.Tooltip",dijit._Widget,{label:"",showDelay:400,connectId:[],position:[],constructor:function(){
this._nodeConnectionsById={};
},_setConnectIdAttr:function(_d){
for(var _e in this._nodeConnectionsById){
this.removeTarget(_e);
}
dojo.forEach(dojo.isArrayLike(_d)?_d:[_d],this.addTarget,this);
},_getConnectIdAttr:function(){
var _f=[];
for(var id in this._nodeConnectionsById){
_f.push(id);
}
return _f;
},addTarget:function(id){
var _10=dojo.byId(id);
if(!_10){
return;
}
if(_10.id in this._nodeConnectionsById){
return;
}
this._nodeConnectionsById[_10.id]=[this.connect(_10,"onmouseenter","_onTargetMouseEnter"),this.connect(_10,"onmouseleave","_onTargetMouseLeave"),this.connect(_10,"onfocus","_onTargetFocus"),this.connect(_10,"onblur","_onTargetBlur")];
if(dojo.isIE&&!_10.style.zoom){
_10.style.zoom=1;
}
},removeTarget:function(_11){
var id=_11.id||_11;
if(id in this._nodeConnectionsById){
dojo.forEach(this._nodeConnectionsById[id],this.disconnect,this);
delete this._nodeConnectionsById[id];
}
},postCreate:function(){
dojo.addClass(this.domNode,"dijitTooltipData");
},startup:function(){
this.inherited(arguments);
var ids=this.connectId;
dojo.forEach(dojo.isArrayLike(ids)?ids:[ids],this.addTarget,this);
},_onTargetMouseEnter:function(e){
this._onHover(e);
},_onTargetMouseLeave:function(e){
this._onUnHover(e);
},_onTargetFocus:function(e){
this._focus=true;
this._onHover(e);
},_onTargetBlur:function(e){
this._focus=false;
this._onUnHover(e);
},_onHover:function(e){
if(!this._showTimer){
var _12=e.target;
this._showTimer=setTimeout(dojo.hitch(this,function(){
this.open(_12);
}),this.showDelay);
}
},_onUnHover:function(e){
if(this._focus){
return;
}
if(this._showTimer){
clearTimeout(this._showTimer);
delete this._showTimer;
}
this.close();
},open:function(_13){
if(this._showTimer){
clearTimeout(this._showTimer);
delete this._showTimer;
}
dijit.showTooltip(this.label||this.domNode.innerHTML,_13,this.position);
this._connectNode=_13;
this.onShow(_13,this.position);
},close:function(){
if(this._connectNode){
dijit.hideTooltip(this._connectNode);
delete this._connectNode;
this.onHide();
}
if(this._showTimer){
clearTimeout(this._showTimer);
delete this._showTimer;
}
},onShow:function(_14,_15){
},onHide:function(){
},uninitialize:function(){
this.close();
this.inherited(arguments);
}});
dijit.Tooltip.defaultPosition=["after","before"];
}
