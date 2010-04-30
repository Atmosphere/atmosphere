/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dijit._editor.plugins.Print"]){
dojo._hasResource["dijit._editor.plugins.Print"]=true;
dojo.provide("dijit._editor.plugins.Print");
dojo.require("dijit._editor._Plugin");
dojo.require("dijit.form.Button");
dojo.require("dojo.i18n");
dojo.requireLocalization("dijit._editor","commands",null,"ROOT,ar,ca,cs,da,de,el,es,fi,fr,he,hu,it,ja,ko,nb,nl,pl,pt,pt-pt,ru,sk,sl,sv,th,tr,zh,zh-tw");
dojo.declare("dijit._editor.plugins.Print",dijit._editor._Plugin,{_initButton:function(){
var _1=dojo.i18n.getLocalization("dijit._editor","commands");
this.button=new dijit.form.Button({label:_1["print"],showLabel:false,iconClass:this.iconClassPrefix+" "+this.iconClassPrefix+"Print",tabIndex:"-1",onClick:dojo.hitch(this,"_print")});
},setEditor:function(_2){
this.editor=_2;
this._initButton();
this.editor.onLoadDeferred.addCallback(dojo.hitch(this,function(){
if(!this.editor.iframe.contentWindow["print"]){
this.button.attr("disabled",true);
}
}));
},_print:function(){
var _3=this.editor.iframe;
if(_3.contentWindow["print"]){
if(!dojo.isOpera&&!dojo.isChrome){
dijit.focus(_3);
_3.contentWindow.print();
}else{
var _4=this.editor.document;
var _5=this.editor.attr("value");
_5="<html><head><meta http-equiv='Content-Type' "+"content='text/html; charset='UTF-8'></head><body>"+_5+"</body></html>";
var _6=window.open("javascript: ''","","status=0,menubar=0,location=0,toolbar=0,"+"width=1,height=1,resizable=0,scrollbars=0");
_6.document.open();
_6.document.write(_5);
_6.document.close();
var _7=[];
var _8=_4.getElementsByTagName("style");
if(_8){
var i;
for(i=0;i<_8.length;i++){
var _9=_8[i].innerHTML;
var _a=_6.document.createElement("style");
_a.appendChild(_6.document.createTextNode(_9));
_6.document.getElementsByTagName("head")[0].appendChild(_a);
}
}
_6.print();
_6.close();
}
}
}});
dojo.subscribe(dijit._scopeName+".Editor.getPlugin",null,function(o){
if(o.plugin){
return;
}
var _b=o.args.name.toLowerCase();
if(_b==="print"){
o.plugin=new dijit._editor.plugins.Print({command:"print"});
}
});
}
