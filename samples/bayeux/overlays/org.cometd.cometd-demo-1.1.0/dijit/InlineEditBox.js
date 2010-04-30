/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dijit.InlineEditBox"]){
dojo._hasResource["dijit.InlineEditBox"]=true;
dojo.provide("dijit.InlineEditBox");
dojo.require("dojo.i18n");
dojo.require("dijit._Widget");
dojo.require("dijit._Container");
dojo.require("dijit.form.Button");
dojo.require("dijit.form.TextBox");
dojo.requireLocalization("dijit","common",null,"ROOT,ar,ca,cs,da,de,el,es,fi,fr,he,hu,it,ja,ko,nb,nl,pl,pt,pt-pt,ru,sk,sl,sv,th,tr,zh,zh-tw");
dojo.declare("dijit.InlineEditBox",dijit._Widget,{editing:false,autoSave:true,buttonSave:"",buttonCancel:"",renderAsHtml:false,editor:"dijit.form.TextBox",editorWrapper:"dijit._InlineEditor",editorParams:{},onChange:function(_1){
},onCancel:function(){
},width:"100%",value:"",noValueIndicator:"<span style='font-family: wingdings; text-decoration: underline;'>&nbsp;&nbsp;&nbsp;&nbsp;&#x270d;&nbsp;&nbsp;&nbsp;&nbsp;</span>",constructor:function(){
this.editorParams={};
},postMixInProperties:function(){
this.inherited(arguments);
this.displayNode=this.srcNodeRef;
var _2={ondijitclick:"_onClick",onmouseover:"_onMouseOver",onmouseout:"_onMouseOut",onfocus:"_onMouseOver",onblur:"_onMouseOut"};
for(var _3 in _2){
this.connect(this.displayNode,_3,_2[_3]);
}
dijit.setWaiRole(this.displayNode,"button");
if(!this.displayNode.getAttribute("tabIndex")){
this.displayNode.setAttribute("tabIndex",0);
}
this.attr("value",this.value||this.displayNode.innerHTML);
},setDisabled:function(_4){
dojo.deprecated("dijit.InlineEditBox.setDisabled() is deprecated.  Use attr('disabled', bool) instead.","","2.0");
this.attr("disabled",_4);
},_setDisabledAttr:function(_5){
this.disabled=_5;
dijit.setWaiState(this.domNode,"disabled",_5);
if(_5){
this.displayNode.removeAttribute("tabIndex");
}else{
this.displayNode.setAttribute("tabIndex",0);
}
},_onMouseOver:function(){
dojo.addClass(this.displayNode,this.disabled?"dijitDisabledClickableRegion":"dijitClickableRegion");
},_onMouseOut:function(){
dojo.removeClass(this.displayNode,this.disabled?"dijitDisabledClickableRegion":"dijitClickableRegion");
},_onClick:function(e){
if(this.disabled){
return;
}
if(e){
dojo.stopEvent(e);
}
this._onMouseOut();
setTimeout(dojo.hitch(this,"edit"),0);
},edit:function(){
if(this.disabled||this.editing){
return;
}
this.editing=true;
this._savedPosition=dojo.style(this.displayNode,"position")||"static";
this._savedOpacity=dojo.style(this.displayNode,"opacity")||"1";
this._savedTabIndex=dojo.attr(this.displayNode,"tabIndex")||"0";
if(this.wrapperWidget){
this.wrapperWidget.editWidget.attr("displayedValue" in this.editorParams?"displayedValue":"value",this.value);
}else{
var _6=dojo.create("span",null,this.domNode,"before");
var _7=dojo.getObject(this.editorWrapper);
this.wrapperWidget=new _7({value:this.value,buttonSave:this.buttonSave,buttonCancel:this.buttonCancel,tabIndex:this._savedTabIndex,editor:this.editor,inlineEditBox:this,sourceStyle:dojo.getComputedStyle(this.displayNode),save:dojo.hitch(this,"save"),cancel:dojo.hitch(this,"cancel")},_6);
}
var ww=this.wrapperWidget;
if(dojo.isIE){
dijit.focus(dijit.getFocus());
}
dojo.style(this.displayNode,{position:"absolute",opacity:"0",display:"none"});
dojo.style(ww.domNode,{position:this._savedPosition,visibility:"visible",opacity:"1"});
dojo.attr(this.displayNode,"tabIndex","-1");
setTimeout(dojo.hitch(this,function(){
ww.focus();
ww._resetValue=ww.getValue();
}),0);
},_onBlur:function(){
this.inherited(arguments);
if(!this.editing){
setTimeout(dojo.hitch(this,function(){
if(this.wrapperWidget){
this.wrapperWidget.destroy();
delete this.wrapperWidget;
}
}),0);
}
},_showText:function(_8){
var ww=this.wrapperWidget;
dojo.style(ww.domNode,{position:"absolute",visibility:"hidden",opacity:"0"});
dojo.style(this.displayNode,{position:this._savedPosition,opacity:this._savedOpacity,display:""});
dojo.attr(this.displayNode,"tabIndex",this._savedTabIndex);
if(_8){
dijit.focus(this.displayNode);
}
},save:function(_9){
if(this.disabled||!this.editing){
return;
}
this.editing=false;
var ww=this.wrapperWidget;
var _a=ww.getValue();
this.attr("value",_a);
setTimeout(dojo.hitch(this,"onChange",_a),0);
this._showText(_9);
},setValue:function(_b){
dojo.deprecated("dijit.InlineEditBox.setValue() is deprecated.  Use attr('value', ...) instead.","","2.0");
return this.attr("value",_b);
},_setValueAttr:function(_c){
this.value=_c=dojo.trim(_c);
if(!this.renderAsHtml){
_c=_c.replace(/&/gm,"&amp;").replace(/</gm,"&lt;").replace(/>/gm,"&gt;").replace(/"/gm,"&quot;").replace(/\n/g,"<br>");
}
this.displayNode.innerHTML=_c||this.noValueIndicator;
},getValue:function(){
dojo.deprecated("dijit.InlineEditBox.getValue() is deprecated.  Use attr('value') instead.","","2.0");
return this.attr("value");
},cancel:function(_d){
if(this.disabled||!this.editing){
return;
}
this.editing=false;
setTimeout(dojo.hitch(this,"onCancel"),0);
this._showText(_d);
}});
dojo.declare("dijit._InlineEditor",[dijit._Widget,dijit._Templated],{templateString:dojo.cache("dijit","templates/InlineEditBox.html","<span dojoAttachPoint=\"editNode\" waiRole=\"presentation\" style=\"position: absolute; visibility:hidden\" class=\"dijitReset dijitInline\"\n\tdojoAttachEvent=\"onkeypress: _onKeyPress\"\n\t><span dojoAttachPoint=\"editorPlaceholder\"></span\n\t><span dojoAttachPoint=\"buttonContainer\"\n\t\t><button class='saveButton' dojoAttachPoint=\"saveButton\" dojoType=\"dijit.form.Button\" dojoAttachEvent=\"onClick:save\" label=\"${buttonSave}\"></button\n\t\t><button class='cancelButton' dojoAttachPoint=\"cancelButton\" dojoType=\"dijit.form.Button\" dojoAttachEvent=\"onClick:cancel\" label=\"${buttonCancel}\"></button\n\t></span\n></span>\n"),widgetsInTemplate:true,postMixInProperties:function(){
this.inherited(arguments);
this.messages=dojo.i18n.getLocalization("dijit","common",this.lang);
dojo.forEach(["buttonSave","buttonCancel"],function(_e){
if(!this[_e]){
this[_e]=this.messages[_e];
}
},this);
},postCreate:function(){
var _f=dojo.getObject(this.editor);
var _10=this.sourceStyle;
var _11="line-height:"+_10.lineHeight+";";
dojo.forEach(["Weight","Family","Size","Style"],function(_12){
_11+="font-"+_12+":"+_10["font"+_12]+";";
},this);
dojo.forEach(["marginTop","marginBottom","marginLeft","marginRight"],function(_13){
this.domNode.style[_13]=_10[_13];
},this);
var _14=this.inlineEditBox.width;
if(_14=="100%"){
_11+="width:100%;";
this.domNode.style.display="block";
}else{
_11+="width:"+(_14+(Number(_14)==_14?"px":""))+";";
}
var _15=this.inlineEditBox.editorParams;
_15.style=_11;
_15["displayedValue" in _f.prototype?"displayedValue":"value"]=this.value;
var ew=this.editWidget=new _f(_15,this.editorPlaceholder);
if(this.inlineEditBox.autoSave){
this.buttonContainer.style.display="none";
this.connect(ew,"onChange","_onChange");
this.connect(ew,"onKeyPress","_onKeyPress");
}else{
if("intermediateChanges" in _f.prototype){
ew.attr("intermediateChanges",true);
this.connect(ew,"onChange","_onIntermediateChange");
this.saveButton.attr("disabled",true);
}
}
},_onIntermediateChange:function(val){
this.saveButton.attr("disabled",(this.getValue()==this._resetValue)||!this.enableSave());
},destroy:function(){
this.editWidget.destroy(true);
this.inherited(arguments);
},getValue:function(){
var ew=this.editWidget;
return String(ew.attr("displayedValue" in ew?"displayedValue":"value"));
},_onKeyPress:function(e){
if(this.inlineEditBox.autoSave&&this.inlineEditBox.editing){
if(e.altKey||e.ctrlKey){
return;
}
if(e.charOrCode==dojo.keys.ESCAPE){
dojo.stopEvent(e);
this.cancel(true);
}else{
if(e.charOrCode==dojo.keys.ENTER&&e.target.tagName=="INPUT"){
dojo.stopEvent(e);
this._onChange();
}
}
}
},_onBlur:function(){
this.inherited(arguments);
if(this.inlineEditBox.autoSave&&this.inlineEditBox.editing){
if(this.getValue()==this._resetValue){
this.cancel(false);
}else{
if(this.enableSave()){
this.save(false);
}
}
}
},_onChange:function(){
if(this.inlineEditBox.autoSave&&this.inlineEditBox.editing&&this.enableSave()){
dojo.style(this.inlineEditBox.displayNode,{display:""});
dijit.focus(this.inlineEditBox.displayNode);
}
},enableSave:function(){
return (this.editWidget.isValid?this.editWidget.isValid():true);
},focus:function(){
this.editWidget.focus();
setTimeout(dojo.hitch(this,function(){
if(this.editWidget.focusNode.tagName=="INPUT"){
dijit.selectInputText(this.editWidget.focusNode);
}
}),0);
}});
}
