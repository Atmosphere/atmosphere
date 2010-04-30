/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dojox.editor.plugins.FindReplace"]){
dojo._hasResource["dojox.editor.plugins.FindReplace"]=true;
dojo.provide("dojox.editor.plugins.FindReplace");
dojo.require("dijit._editor._Plugin");
dojo.require("dijit.Toolbar");
dojo.require("dijit.form.TextBox");
dojo.require("dijit.form.CheckBox");
dojo.require("dijit.form.Button");
dojo.require("dijit.TooltipDialog");
dojo.require("dijit.Menu");
dojo.require("dijit.CheckedMenuItem");
dojo.require("dojox.editor.plugins.ToolbarLineBreak");
dojo.require("dojo.i18n");
dojo.require("dojo.string");
dojo.requireLocalization("dojox.editor.plugins","FindReplace",null,"ROOT,cs,de,es,fr,hu,it,ja,ko,pl,pt,ru,zh,zh-tw");
dojo.experimental("dojox.editor.plugins.FindReplace");
dojo.declare("dojox.editor.plugins._FindReplaceTextBox",[dijit._Widget,dijit._Templated],{textId:"",label:"",widget:null,widgetsInTemplate:true,templateString:"<span style='white-space: nowrap' class='dijit dijitReset dijitInline findReplaceTextBox'>"+"<label class='dijitLeft dijitInline' for='${textId}'>${label}</label>"+"<input dojoType='dijit.form.TextBox' required=false intermediateChanges='true'"+"tabIndex='-1' id='${textId}' dojoAttachPoint='textBox' value='' style='width: 20em;'/>"+"</span>",postMixInProperties:function(){
this.inherited(arguments);
this.id=dijit.getUniqueId(this.declaredClass.replace(/\./g,"_"));
this.textId=this.id+"_text";
this.inherited(arguments);
},postCreate:function(){
this.textBox.attr("value","");
this.disabled=this.textBox.attr("disabled");
this.connect(this.textBox,"onChange","onChange");
},_setValueAttr:function(_1){
this.value=_1;
this.textBox.attr("value",_1);
},focus:function(){
this.textBox.focus();
},_setDisabledAttr:function(_2){
this.disabled=_2;
this.textBox.attr("disabled",_2);
},onChange:function(_3){
this.value=_3;
}});
dojo.declare("dojox.editor.plugins._FindReplaceCheckBox",[dijit._Widget,dijit._Templated],{checkId:"",label:"",widget:null,widgetsInTemplate:true,templateString:"<span style='white-space: nowrap' class='dijit dijitReset dijitInline findReplaceCheckBox'>"+"<input dojoType='dijit.form.CheckBox' required=false "+"tabIndex='-1' id='${checkId}' dojoAttachPoint='checkBox' value=''/>"+"<label class='dijitLeft dijitInline' for='${checkId}'>${label}</label>"+"</span>",postMixInProperties:function(){
this.inherited(arguments);
this.id=dijit.getUniqueId(this.declaredClass.replace(/\./g,"_"));
this.checkId=this.id+"_check";
this.inherited(arguments);
},postCreate:function(){
this.checkBox.attr("checked",false);
this.disabled=this.checkBox.attr("disabled");
this.checkBox.isFocusable=function(){
return false;
};
},_setValueAttr:function(_4){
this.checkBox.attr("value",_4);
},_getValueAttr:function(){
return this.checkBox.attr("value");
},focus:function(){
this.checkBox.focus();
},_setDisabledAttr:function(_5){
this.disabled=_5;
this.checkBox.attr("disabled",_5);
}});
dojo.declare("dojox.editor.plugins.FindReplace",[dijit._editor._Plugin],{buttonClass:dijit.form.ToggleButton,iconClassPrefix:"dijitAdditionalEditorIcon",_initButton:function(){
var _6=dojo.i18n.getLocalization("dojox.editor.plugins","FindReplace");
this.button=new dijit.form.ToggleButton({label:_6["findReplace"],showLabel:false,iconClass:this.iconClassPrefix+" "+this.iconClassPrefix+"FindReplace",tabIndex:"-1",onChange:dojo.hitch(this,"_toggleFindReplace")});
if(dojo.isOpera){
this.button.attr("disabled",true);
}
this.connect(this.button,"attr",dojo.hitch(this,function(_7,_8){
if(_7==="disabled"){
this._toggleFindReplace((!_8&&this._displayed),true);
}
}));
},setEditor:function(_9){
this.editor=_9;
this._initButton();
},toggle:function(){
this.button.attr("checked",!this.button.attr("checked"));
},_toggleFindReplace:function(_a,_b){
if(_a&&!dojo.isOpera){
dojo.style(this._frToolbar.domNode,"display","block");
if(!_b){
this._displayed=true;
}
}else{
dojo.style(this._frToolbar.domNode,"display","none");
if(!_b){
this._displayed=false;
}
}
this.editor.resize();
},setToolbar:function(_c){
this.inherited(arguments);
if(!dojo.isOpera){
var _d=dojo.i18n.getLocalization("dojox.editor.plugins","FindReplace");
this._frToolbar=new dijit.Toolbar();
dojo.style(this._frToolbar.domNode,"display","none");
dojo.place(this._frToolbar.domNode,_c.domNode,"after");
this._frToolbar.startup();
this._caseSensitive=new dojox.editor.plugins._FindReplaceCheckBox({label:_d["matchCase"]});
this._backwards=new dojox.editor.plugins._FindReplaceCheckBox({label:_d["backwards"]});
this._replaceAll=new dojox.editor.plugins._FindReplaceCheckBox({label:_d["replaceAll"]});
this._findField=new dojox.editor.plugins._FindReplaceTextBox({label:_d.findLabel});
this._frToolbar.addChild(this._findField);
this._findButton=new dijit.form.Button({label:_d["findButton"],showLabel:true,iconClass:this.iconClassPrefix+" "+this.iconClassPrefix+"FindRun"});
this._frToolbar.addChild(this._findButton);
this._frToolbar.addChild(this._caseSensitive);
this._frToolbar.addChild(this._backwards);
this._frToolbar.addChild(new dojox.editor.plugins._ToolbarLineBreak());
this._replaceField=new dojox.editor.plugins._FindReplaceTextBox({label:_d.replaceLabel});
this._frToolbar.addChild(this._replaceField);
this._replaceButton=new dijit.form.Button({label:_d["replaceButton"],showLabel:true,iconClass:this.iconClassPrefix+" "+this.iconClassPrefix+"ReplaceRun"});
this._frToolbar.addChild(this._replaceButton);
this._frToolbar.addChild(this._replaceAll);
this._findButton.attr("disabled",true);
this._replaceButton.attr("disabled",true);
this.connect(this._findField,"onChange","_checkButtons");
this.connect(this._replaceField,"onChange","_checkButtons");
this.connect(this._findButton,"onClick","_find");
this.connect(this._replaceButton,"onClick","_replace");
this._replDialog=new dijit.TooltipDialog();
this._replDialog.startup();
this._replDialog.attr("content","");
this._dialogTemplate=_d.replaceDialogText;
}
},_checkButtons:function(){
var _e=this._findField.attr("value");
var _f=this._replaceField.attr("value");
if(_e){
this._findButton.attr("disabled",false);
}else{
this._findButton.attr("disabled",true);
}
if(_e&&_f&&_e!==_f){
this._replaceButton.attr("disabled",false);
}else{
this._replaceButton.attr("disabled",true);
}
},_find:function(){
var txt=this._findField.attr("value");
if(txt){
var _10=this._caseSensitive.attr("value");
var _11=this._backwards.attr("value");
return this._findText(txt,_10,_11);
}
return false;
},_replace:function(){
var ed=this.editor;
ed.focus();
var txt=this._findField.attr("value");
var _12=this._replaceField.attr("value");
var _13=0;
if(txt){
if(this._replaceDialogTimeout){
clearTimeout(this._replaceDialogTimeout);
this._replaceDialogTimeout=null;
dijit.popup.close(this._replDialog);
}
var _14=this._replaceAll.attr("value");
var _15=this._caseSensitive.attr("value");
var _16=this._backwards.attr("value");
var _17=dojo.withGlobal(ed.window,"getSelectedText",dijit._editor.selection,[null]);
if(dojo.isMoz){
txt=dojo.trim(txt);
_17=dojo.trim(_17);
}
var _18=this._filterRegexp(txt,!_15);
if(_17&&_18.test(_17)){
ed.execCommand("inserthtml",_12);
_13++;
}
if(_14){
var _19=this._findText(txt,_15,_16);
var _1a=function(){
ed.execCommand("inserthtml",_12);
_13++;
_19=this._findText(txt,_15,_16);
if(_19){
setTimeout(dojo.hitch(this,_1a),10);
}else{
this._replDialog.attr("content",dojo.string.substitute(this._dialogTemplate,{"0":""+_13}));
dijit.popup.open({popup:this._replDialog,around:this._replaceButton.domNode});
this._replaceDialogTimeout=setTimeout(dojo.hitch(this,function(){
clearTimeout(this._replaceDialogTimeout);
this._replaceDialogTimeout=null;
dijit.popup.close(this._replDialog);
}),5000);
}
};
if(_19){
var _1b=dojo.hitch(this,_1a);
_1b();
}
}
}
},_findText:function(txt,_1c,_1d){
var ed=this.editor;
var win=ed.window;
var _1e=false;
if(txt){
if(win.find){
_1e=win.find(txt,_1c,_1d,false,false,false,false);
}else{
var doc=ed.document;
if(doc.selection){
this.editor.focus();
var _1f=doc.body.createTextRange();
var _20=doc.selection?doc.selection.createRange():null;
if(_20){
if(_1d){
_1f.setEndPoint("EndToStart",_20);
}else{
_1f.setEndPoint("StartToEnd",_20);
}
}
var _21=_1c?4:0;
if(_1d){
_21=_21|1;
}
_1e=_1f.findText(txt,null,_21);
if(_1e){
_1f.select();
}
}
}
}
return _1e;
},_filterRegexp:function(_22,_23){
var rxp="";
var c=null;
for(var i=0;i<_22.length;i++){
c=_22.charAt(i);
switch(c){
case "\\":
rxp+=c;
i++;
rxp+=_22.charAt(i);
break;
case "$":
case "^":
case "/":
case "+":
case ".":
case "|":
case "(":
case ")":
case "{":
case "}":
case "[":
case "]":
rxp+="\\";
default:
rxp+=c;
}
}
rxp="^"+rxp+"$";
if(_23){
return new RegExp(rxp,"mi");
}else{
return new RegExp(rxp,"m");
}
},destroy:function(){
this.inherited(arguments);
if(this._replaceDialogTimeout){
clearTimeout(this._replaceDialogTimeout);
this._replaceDialogTimeout=null;
dijit.popup.close(this._replDialog);
}
if(this._frToolbar){
this._frToolbar.destroyRecursive();
this._frToolbar=null;
}
if(this._replDialog){
this._replDialog.destroyRecursive();
this._replDialog=null;
}
}});
dojo.subscribe(dijit._scopeName+".Editor.getPlugin",null,function(o){
if(o.plugin){
return;
}
var _24=o.args.name.toLowerCase();
if(_24==="findreplace"){
o.plugin=new dojox.editor.plugins.FindReplace({});
}
});
}
