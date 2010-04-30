/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dijit._editor.plugins.FontChoice"]){
dojo._hasResource["dijit._editor.plugins.FontChoice"]=true;
dojo.provide("dijit._editor.plugins.FontChoice");
dojo.require("dijit._editor._Plugin");
dojo.require("dijit._editor.range");
dojo.require("dijit.form.FilteringSelect");
dojo.require("dojo.data.ItemFileReadStore");
dojo.require("dojo.i18n");
dojo.requireLocalization("dijit._editor","FontChoice",null,"ROOT,ar,ca,cs,da,de,el,es,fi,fr,he,hu,it,ja,ko,nb,nl,pl,pt,pt-pt,ru,sk,sl,sv,th,tr,zh,zh-tw");
dojo.declare("dijit._editor.plugins._FontDropDown",[dijit._Widget,dijit._Templated],{label:"",widgetsInTemplate:true,plainText:false,templateString:"<span style='white-space: nowrap' class='dijit dijitReset dijitInline'>"+"<label class='dijitLeft dijitInline' for='${selectId}'>${label}</label>"+"<input dojoType='dijit.form.FilteringSelect' required=false labelType=html labelAttr=label searchAttr=name "+"tabIndex='-1' id='${selectId}' dojoAttachPoint='select' value=''/>"+"</span>",postMixInProperties:function(){
this.inherited(arguments);
this.strings=dojo.i18n.getLocalization("dijit._editor","FontChoice");
this.label=this.strings[this.command];
this.id=dijit.getUniqueId(this.declaredClass.replace(/\./g,"_"));
this.selectId=this.id+"_select";
this.inherited(arguments);
},postCreate:function(){
var _1=dojo.map(this.values,function(_2){
var _3=this.strings[_2]||_2;
return {label:this.getLabel(_2,_3),name:_3,value:_2};
},this);
this.select.store=new dojo.data.ItemFileReadStore({data:{identifier:"value",items:_1}});
this.select.attr("value","",false);
this.disabled=this.select.attr("disabled");
},_setValueAttr:function(_4,_5){
_5=_5!==false?true:false;
this.select.attr("value",dojo.indexOf(this.values,_4)<0?"":_4,_5);
if(!_5){
this.select._lastValueReported=null;
}
},_getValueAttr:function(){
return this.select.attr("value");
},focus:function(){
this.select.focus();
},_setDisabledAttr:function(_6){
this.disabled=_6;
this.select.attr("disabled",_6);
}});
dojo.declare("dijit._editor.plugins._FontNameDropDown",dijit._editor.plugins._FontDropDown,{generic:false,command:"fontName",postMixInProperties:function(){
if(!this.values){
this.values=this.generic?["serif","sans-serif","monospace","cursive","fantasy"]:["Arial","Times New Roman","Comic Sans MS","Courier New"];
}
this.inherited(arguments);
},getLabel:function(_7,_8){
if(this.plainText){
return _8;
}else{
return "<div style='font-family: "+_7+"'>"+_8+"</div>";
}
},_setValueAttr:function(_9,_a){
_a=_a!==false?true:false;
if(this.generic){
var _b={"Arial":"sans-serif","Helvetica":"sans-serif","Myriad":"sans-serif","Times":"serif","Times New Roman":"serif","Comic Sans MS":"cursive","Apple Chancery":"cursive","Courier":"monospace","Courier New":"monospace","Papyrus":"fantasy"};
_9=_b[_9]||_9;
}
this.inherited(arguments,[_9,_a]);
}});
dojo.declare("dijit._editor.plugins._FontSizeDropDown",dijit._editor.plugins._FontDropDown,{command:"fontSize",values:[1,2,3,4,5,6,7],getLabel:function(_c,_d){
if(this.plainText){
return _d;
}else{
return "<font size="+_c+"'>"+_d+"</font>";
}
},_setValueAttr:function(_e,_f){
_f=_f!==false?true:false;
if(_e.indexOf&&_e.indexOf("px")!=-1){
var _10=parseInt(_e,10);
_e={10:1,13:2,16:3,18:4,24:5,32:6,48:7}[_10]||_e;
}
this.inherited(arguments,[_e,_f]);
}});
dojo.declare("dijit._editor.plugins._FormatBlockDropDown",dijit._editor.plugins._FontDropDown,{command:"formatBlock",values:["p","h1","h2","h3","pre"],getLabel:function(_11,_12){
if(this.plainText){
return _12;
}else{
return "<"+_11+">"+_12+"</"+_11+">";
}
}});
dojo.declare("dijit._editor.plugins.FontChoice",dijit._editor._Plugin,{useDefaultCommand:false,_initButton:function(){
var _13={fontName:dijit._editor.plugins._FontNameDropDown,fontSize:dijit._editor.plugins._FontSizeDropDown,formatBlock:dijit._editor.plugins._FormatBlockDropDown}[this.command],_14=this.params;
if(this.params.custom){
_14.values=this.params.custom;
}
this.button=new _13(_14);
this.connect(this.button.select,"onChange",function(_15){
this.editor.focus();
if(this.command=="fontName"&&_15.indexOf(" ")!=-1){
_15="'"+_15+"'";
}
this.editor.execCommand(this.command,_15);
});
},updateState:function(){
var _16=this.editor;
var _17=this.command;
if(!_16||!_16.isLoaded||!_17.length){
return;
}
if(this.button){
var _18;
try{
_18=_16.queryCommandValue(_17)||"";
}
catch(e){
_18="";
}
var _19=dojo.isString(_18)&&_18.match(/'([^']*)'/);
if(_19){
_18=_19[1];
}
if(!_18&&_17==="formatBlock"){
var _1a;
var sel=dijit.range.getSelection(this.editor.window);
if(sel&&sel.rangeCount>0){
var _1b=sel.getRangeAt(0);
if(_1b){
_1a=_1b.endContainer;
}
}
while(_1a&&_1a!==_16.editNode&&_1a!==_16.document){
var tg=_1a.tagName?_1a.tagName.toLowerCase():"";
if(tg&&dojo.indexOf(this.button.values,tg)>-1){
_18=tg;
break;
}
_1a=_1a.parentNode;
}
}
if(_18!==this.button.attr("value")){
this.button.attr("value",_18,false);
}
}
}});
dojo.subscribe(dijit._scopeName+".Editor.getPlugin",null,function(o){
if(o.plugin){
return;
}
switch(o.args.name){
case "fontName":
case "fontSize":
case "formatBlock":
o.plugin=new dijit._editor.plugins.FontChoice({command:o.args.name,plainText:o.args.plainText?o.args.plainText:false});
}
});
}
