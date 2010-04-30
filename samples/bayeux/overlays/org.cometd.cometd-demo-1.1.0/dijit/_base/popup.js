/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dijit._base.popup"]){
dojo._hasResource["dijit._base.popup"]=true;
dojo.provide("dijit._base.popup");
dojo.require("dijit._base.focus");
dojo.require("dijit._base.place");
dojo.require("dijit._base.window");
dijit.popup=new function(){
var _1=[],_2=1000,_3=1;
this.moveOffScreen=function(_4){
var s=_4.style;
s.visibility="hidden";
s.position="absolute";
s.top="-9999px";
if(s.display=="none"){
s.display="";
}
dojo.body().appendChild(_4);
};
var _5=function(){
for(var pi=_1.length-1;pi>0&&_1[pi].parent===_1[pi-1].widget;pi--){
}
return _1[pi];
};
var _6=[];
this.open=function(_7){
var _8=_7.popup,_9=_7.orient||(dojo._isBodyLtr()?{"BL":"TL","BR":"TR","TL":"BL","TR":"BR"}:{"BR":"TR","BL":"TL","TR":"BR","TL":"BL"}),_a=_7.around,id=(_7.around&&_7.around.id)?(_7.around.id+"_dropdown"):("popup_"+_3++);
var _b=_6.pop(),_c,_d;
if(!_b){
_c=dojo.create("div",{"class":"dijitPopup"},dojo.body());
dijit.setWaiRole(_c,"presentation");
}else{
_c=_b[0];
_d=_b[1];
}
dojo.attr(_c,{id:id,style:{zIndex:_2+_1.length,visibility:"hidden",top:"-9999px"},dijitPopupParent:_7.parent?_7.parent.id:""});
var s=_8.domNode.style;
s.display="";
s.visibility="";
s.position="";
s.top="0px";
_c.appendChild(_8.domNode);
if(!_d){
_d=new dijit.BackgroundIframe(_c);
}else{
_d.resize(_c);
}
var _e=_a?dijit.placeOnScreenAroundElement(_c,_a,_9,_8.orient?dojo.hitch(_8,"orient"):null):dijit.placeOnScreen(_c,_7,_9=="R"?["TR","BR","TL","BL"]:["TL","BL","TR","BR"],_7.padding);
_c.style.visibility="visible";
var _f=[];
_f.push(dojo.connect(_c,"onkeypress",this,function(evt){
if(evt.charOrCode==dojo.keys.ESCAPE&&_7.onCancel){
dojo.stopEvent(evt);
_7.onCancel();
}else{
if(evt.charOrCode===dojo.keys.TAB){
dojo.stopEvent(evt);
var _10=_5();
if(_10&&_10.onCancel){
_10.onCancel();
}
}
}
}));
if(_8.onCancel){
_f.push(dojo.connect(_8,"onCancel",_7.onCancel));
}
_f.push(dojo.connect(_8,_8.onExecute?"onExecute":"onChange",function(){
var _11=_5();
if(_11&&_11.onExecute){
_11.onExecute();
}
}));
_1.push({wrapper:_c,iframe:_d,widget:_8,parent:_7.parent,onExecute:_7.onExecute,onCancel:_7.onCancel,onClose:_7.onClose,handlers:_f});
if(_8.onOpen){
_8.onOpen(_e);
}
return _e;
};
this.close=function(_12){
while(dojo.some(_1,function(_13){
return _13.widget==_12;
})){
var top=_1.pop(),_14=top.wrapper,_15=top.iframe,_16=top.widget,_17=top.onClose;
if(_16.onClose){
_16.onClose();
}
dojo.forEach(top.handlers,dojo.disconnect);
if(_16&&_16.domNode){
this.moveOffScreen(_16.domNode);
}
_14.style.top="-9999px";
_14.style.visibility="hidden";
_6.push([_14,_15]);
if(_17){
_17();
}
}
};
}();
dijit._frames=new function(){
var _18=[];
this.pop=function(){
var _19;
if(_18.length){
_19=_18.pop();
_19.style.display="";
}else{
if(dojo.isIE){
var _1a=dojo.config["dojoBlankHtmlUrl"]||(dojo.moduleUrl("dojo","resources/blank.html")+"")||"javascript:\"\"";
var _1b="<iframe src='"+_1a+"'"+" style='position: absolute; left: 0px; top: 0px;"+"z-index: -1; filter:Alpha(Opacity=\"0\");'>";
_19=dojo.doc.createElement(_1b);
}else{
_19=dojo.create("iframe");
_19.src="javascript:\"\"";
_19.className="dijitBackgroundIframe";
dojo.style(_19,"opacity",0.1);
}
_19.tabIndex=-1;
}
return _19;
};
this.push=function(_1c){
_1c.style.display="none";
_18.push(_1c);
};
}();
dijit.BackgroundIframe=function(_1d){
if(!_1d.id){
throw new Error("no id");
}
if(dojo.isIE||dojo.isMoz){
var _1e=dijit._frames.pop();
_1d.appendChild(_1e);
if(dojo.isIE<7){
this.resize(_1d);
this._conn=dojo.connect(_1d,"onresize",this,function(){
this.resize(_1d);
});
}else{
dojo.style(_1e,{width:"100%",height:"100%"});
}
this.iframe=_1e;
}
};
dojo.extend(dijit.BackgroundIframe,{resize:function(_1f){
if(this.iframe&&dojo.isIE<7){
dojo.style(this.iframe,{width:_1f.offsetWidth+"px",height:_1f.offsetHeight+"px"});
}
},destroy:function(){
if(this._conn){
dojo.disconnect(this._conn);
this._conn=null;
}
if(this.iframe){
dijit._frames.push(this.iframe);
delete this.iframe;
}
}});
}
