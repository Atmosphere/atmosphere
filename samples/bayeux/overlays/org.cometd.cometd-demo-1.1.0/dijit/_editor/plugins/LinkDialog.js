/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dijit._editor.plugins.LinkDialog"]){
dojo._hasResource["dijit._editor.plugins.LinkDialog"]=true;
dojo.provide("dijit._editor.plugins.LinkDialog");
dojo.require("dijit._Widget");
dojo.require("dijit._Templated");
dojo.require("dijit._editor._Plugin");
dojo.require("dijit.TooltipDialog");
dojo.require("dijit.form.Button");
dojo.require("dijit.form.ValidationTextBox");
dojo.require("dijit.form.Select");
dojo.require("dijit._editor.range");
dojo.require("dojo.i18n");
dojo.require("dojo.string");
dojo.requireLocalization("dijit","common",null,"ROOT,ar,ca,cs,da,de,el,es,fi,fr,he,hu,it,ja,ko,nb,nl,pl,pt,pt-pt,ru,sk,sl,sv,th,tr,zh,zh-tw");
dojo.requireLocalization("dijit._editor","LinkDialog",null,"ROOT,ar,ca,cs,da,de,el,es,fi,fr,he,hu,it,ja,ko,nb,nl,pl,pt,pt-pt,ru,sk,sl,sv,th,tr,zh,zh-tw");
dojo.declare("dijit._editor.plugins.LinkDialog",dijit._editor._Plugin,{buttonClass:dijit.form.DropDownButton,useDefaultCommand:false,urlRegExp:"((https?|ftps?|file)\\://|./|/|)(/[a-zA-Z]{1,1}:/|)(((?:(?:[\\da-zA-Z](?:[-\\da-zA-Z]{0,61}[\\da-zA-Z])?)\\.)*(?:[a-zA-Z](?:[-\\da-zA-Z]{0,80}[\\da-zA-Z])?)\\.?)|(((\\d|[1-9]\\d|1\\d\\d|2[0-4]\\d|25[0-5])\\.){3}(\\d|[1-9]\\d|1\\d\\d|2[0-4]\\d|25[0-5])|(0[xX]0*[\\da-fA-F]?[\\da-fA-F]\\.){3}0[xX]0*[\\da-fA-F]?[\\da-fA-F]|(0+[0-3][0-7][0-7]\\.){3}0+[0-3][0-7][0-7]|(0|[1-9]\\d{0,8}|[1-3]\\d{9}|4[01]\\d{8}|42[0-8]\\d{7}|429[0-3]\\d{6}|4294[0-8]\\d{5}|42949[0-5]\\d{4}|429496[0-6]\\d{3}|4294967[01]\\d{2}|42949672[0-8]\\d|429496729[0-5])|0[xX]0*[\\da-fA-F]{1,8}|([\\da-fA-F]{1,4}\\:){7}[\\da-fA-F]{1,4}|([\\da-fA-F]{1,4}\\:){6}((\\d|[1-9]\\d|1\\d\\d|2[0-4]\\d|25[0-5])\\.){3}(\\d|[1-9]\\d|1\\d\\d|2[0-4]\\d|25[0-5])))(\\:\\d+)?(/(?:[^?#\\s/]+/)*(?:[^?#\\s/]+(?:\\?[^?#\\s/]*)?(?:#.*)?)?)?",htmlTemplate:"<a href=\"${urlInput}\" _djrealurl=\"${urlInput}\""+" target=\"${targetSelect}\""+">${textInput}</a>",tag:"a",_hostRxp:new RegExp("^((([^\\[:]+):)?([^@]+)@)?(\\[([^\\]]+)\\]|([^\\[:]*))(:([0-9]+))?$"),linkDialogTemplate:["<table><tr><td>","<label for='${id}_urlInput'>${url}</label>","</td><td>","<input dojoType='dijit.form.ValidationTextBox' regExp='${urlRegExp}' required='true' "+"id='${id}_urlInput' name='urlInput' intermediateChanges='true'>","</td></tr><tr><td>","<label for='${id}_textInput'>${text}</label>","</td><td>","<input dojoType='dijit.form.ValidationTextBox' required='true' id='${id}_textInput' "+"name='textInput' intermediateChanges='true'>","</td></tr><tr><td>","<label for='${id}_targetSelect'>${target}</label>","</td><td>","<select id='${id}_targetSelect' name='targetSelect' dojoType='dijit.form.Select'>","<option selected='selected' value='_self'>${currentWindow}</option>","<option value='_blank'>${newWindow}</option>","<option value='_top'>${topWindow}</option>","<option value='_parent'>${parentWindow}</option>","</select>","</td></tr><tr><td colspan='2'>","<button dojoType='dijit.form.Button' type='submit' id='${id}_setButton'>${set}</button>","<button dojoType='dijit.form.Button' type='button' id='${id}_cancelButton'>${buttonCancel}</button>","</td></tr></table>"].join(""),_initButton:function(){
var _1=this;
this.tag=this.command=="insertImage"?"img":"a";
var _2=dojo.mixin(dojo.i18n.getLocalization("dijit","common",this.lang),dojo.i18n.getLocalization("dijit._editor","LinkDialog",this.lang));
var _3=(this.dropDown=new dijit.TooltipDialog({title:_2[this.command+"Title"],execute:dojo.hitch(this,"setValue"),onOpen:function(){
_1._onOpenDialog();
dijit.TooltipDialog.prototype.onOpen.apply(this,arguments);
},onCancel:function(){
setTimeout(dojo.hitch(_1,"_onCloseDialog"),0);
}}));
_2.urlRegExp=this.urlRegExp;
_2.id=dijit.getUniqueId(this.editor.id);
this._uniqueId=_2.id;
this._setContent(_3.title+"<div style='border-bottom: 1px black solid;padding-bottom:2pt;margin-bottom:4pt'></div>"+dojo.string.substitute(this.linkDialogTemplate,_2));
_3.startup();
this._urlInput=dijit.byId(this._uniqueId+"_urlInput");
this._textInput=dijit.byId(this._uniqueId+"_textInput");
this._setButton=dijit.byId(this._uniqueId+"_setButton");
this.connect(dijit.byId(this._uniqueId+"_cancelButton"),"onClick",function(){
this.dropDown.onCancel();
});
if(this._urlInput){
this.connect(this._urlInput,"onChange","_checkAndFixInput");
}
if(this._textInput){
this.connect(this._textInput,"onChange","_checkAndFixInput");
}
this._connectTagEvents();
this.inherited(arguments);
},_checkAndFixInput:function(){
var _4=this;
var _5=this._urlInput.attr("value");
var _6=function(_7){
var _8=false;
if(_7&&_7.length>7){
_7=dojo.trim(_7);
if(_7.indexOf("/")>0){
if(_7.indexOf("://")===-1){
if(_7.charAt(0)!=="/"&&_7.indexOf("./")!==0){
if(_4._hostRxp.test(_7)){
_8=true;
}
}
}
}
}
if(_8){
_4._urlInput.attr("value","http://"+_7);
}
_4._setButton.attr("disabled",!_4._isValid());
};
if(this._delayedCheck){
clearTimeout(this._delayedCheck);
this._delayedCheck=null;
}
this._delayedCheck=setTimeout(function(){
_6(_5);
},250);
},_connectTagEvents:function(){
this.editor.onLoadDeferred.addCallback(dojo.hitch(this,function(){
this.connect(this.editor.editNode,"ondblclick",this._onDblClick);
}));
},_isValid:function(){
return this._urlInput.isValid()&&this._textInput.isValid();
},_setContent:function(_9){
this.dropDown.attr("content",_9);
},_checkValues:function(_a){
if(_a&&_a.urlInput){
_a.urlInput=_a.urlInput.replace(/"/g,"&quot;");
}
return _a;
},setValue:function(_b){
this._onCloseDialog();
if(dojo.isIE){
var _c=dijit.range.getSelection(this.editor.window);
var _d=_c.getRangeAt(0);
var a=_d.endContainer;
if(a.nodeType===3){
a=a.parentNode;
}
if(a&&(a.nodeName&&a.nodeName.toLowerCase()!==this.tag)){
a=dojo.withGlobal(this.editor.window,"getSelectedElement",dijit._editor.selection,[this.tag]);
}
if(a&&(a.nodeName&&a.nodeName.toLowerCase()===this.tag)){
if(this.editor.queryCommandEnabled("unlink")){
dojo.withGlobal(this.editor.window,"selectElementChildren",dijit._editor.selection,[a]);
this.editor.execCommand("unlink");
}
}
}
_b=this._checkValues(_b);
this.editor.execCommand("inserthtml",dojo.string.substitute(this.htmlTemplate,_b));
},_onCloseDialog:function(){
this.editor.focus();
},_getCurrentValues:function(a){
var _e,_f,_10;
if(a&&a.tagName.toLowerCase()===this.tag){
_e=a.getAttribute("_djrealurl");
_10=a.getAttribute("target")||"_self";
_f=a.textContent||a.innerText;
dojo.withGlobal(this.editor.window,"selectElement",dijit._editor.selection,[a,true]);
}else{
_f=dojo.withGlobal(this.editor.window,dijit._editor.selection.getSelectedText);
}
return {urlInput:_e||"",textInput:_f||"",targetSelect:_10||""};
},_onOpenDialog:function(){
var a;
if(dojo.isIE){
var sel=dijit.range.getSelection(this.editor.window);
var _11=sel.getRangeAt(0);
a=_11.endContainer;
if(a.nodeType===3){
a=a.parentNode;
}
if(a&&(a.nodeName&&a.nodeName.toLowerCase()!==this.tag)){
a=dojo.withGlobal(this.editor.window,"getSelectedElement",dijit._editor.selection,[this.tag]);
}
}else{
a=dojo.withGlobal(this.editor.window,"getAncestorElement",dijit._editor.selection,[this.tag]);
}
this.dropDown.reset();
this._setButton.attr("disabled",true);
this.dropDown.attr("value",this._getCurrentValues(a));
},_onDblClick:function(e){
if(e&&e.target){
var t=e.target;
var tg=t.tagName?t.tagName.toLowerCase():"";
if(tg===this.tag){
this.editor.onDisplayChanged();
dojo.withGlobal(this.editor.window,"selectElement",dijit._editor.selection,[t]);
setTimeout(dojo.hitch(this,function(){
this.button.attr("disabled",false);
this.button.openDropDown();
}),10);
}
}
}});
dojo.declare("dijit._editor.plugins.ImgLinkDialog",[dijit._editor.plugins.LinkDialog],{linkDialogTemplate:["<table><tr><td>","<label for='${id}_urlInput'>${url}</label>","</td><td>","<input dojoType='dijit.form.ValidationTextBox' regExp='${urlRegExp}' "+"required='true' id='${id}_urlInput' name='urlInput' intermediateChanges='true'>","</td></tr><tr><td>","<label for='${id}_textInput'>${text}</label>","</td><td>","<input dojoType='dijit.form.ValidationTextBox' required='false' id='${id}_textInput' "+"name='textInput' intermediateChanges='true'>","</td></tr><tr><td>","</td><td>","</td></tr><tr><td colspan='2'>","<button dojoType='dijit.form.Button' type='submit' id='${id}_setButton'>${set}</button>","<button dojoType='dijit.form.Button' type='button' id='${id}_cancelButton'>${buttonCancel}</button>","</td></tr></table>"].join(""),htmlTemplate:"<img src=\"${urlInput}\" _djrealurl=\"${urlInput}\" alt=\"${textInput}\" />",tag:"img",_getCurrentValues:function(img){
var url,_12;
if(img&&img.tagName.toLowerCase()===this.tag){
url=img.getAttribute("_djrealurl");
_12=img.getAttribute("alt");
dojo.withGlobal(this.editor.window,"selectElement",dijit._editor.selection,[img,true]);
}else{
_12=dojo.withGlobal(this.editor.window,dijit._editor.selection.getSelectedText);
}
return {urlInput:url||"",textInput:_12||""};
},_isValid:function(){
return this._urlInput.isValid();
},_connectTagEvents:function(){
this.inherited(arguments);
this.editor.onLoadDeferred.addCallback(dojo.hitch(this,function(){
this.connect(this.editor.editNode,"onclick",this._selectTag);
}));
},_selectTag:function(e){
if(e&&e.target){
var t=e.target;
var tg=t.tagName?t.tagName.toLowerCase():"";
if(tg===this.tag){
dojo.withGlobal(this.editor.window,"selectElement",dijit._editor.selection,[t]);
}
}
},_checkValues:function(_13){
if(_13&&_13.urlInput){
_13.urlInput=_13.urlInput.replace(/"/g,"&quot;");
}
if(_13&&_13.textInput){
_13.textInput=_13.textInput.replace(/"/g,"&quot;");
}
return _13;
}});
dojo.subscribe(dijit._scopeName+".Editor.getPlugin",null,function(o){
if(o.plugin){
return;
}
switch(o.args.name){
case "createLink":
o.plugin=new dijit._editor.plugins.LinkDialog({command:o.args.name});
break;
case "insertImage":
o.plugin=new dijit._editor.plugins.ImgLinkDialog({command:o.args.name});
break;
}
});
}
