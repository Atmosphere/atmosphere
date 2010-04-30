/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dojox.editor.plugins.Smiley"]){
dojo._hasResource["dojox.editor.plugins.Smiley"]=true;
dojo.provide("dojox.editor.plugins.Smiley");
dojo.experimental("dojox.editor.plugins.Smiley");
dojo.require("dojo.i18n");
dojo.require("dijit._editor._Plugin");
dojo.require("dijit.form.ToggleButton");
dojo.require("dijit.form.DropDownButton");
dojo.require("dojox.editor.plugins._SmileyPalette");
dojo.requireLocalization("dojox.editor.plugins","Smiley",null,"ROOT,cs,de,es,fr,hu,it,ja,ko,pl,pt,ru,zh,zh-tw");
dojo.declare("dojox.editor.plugins.Smiley",dijit._editor._Plugin,{iconClassPrefix:"dijitAdditionalEditorIcon",_initButton:function(){
this.dropDown=new dojox.editor.plugins._SmileyPalette();
this.connect(this.dropDown,"onChange",function(_1){
this.button.closeDropDown();
this.editor.focus();
this.editor.execCommand("inserthtml",_1);
});
var _2=dojo.i18n.getLocalization("dojox.editor.plugins","Smiley");
this.button=new dijit.form.DropDownButton({label:_2.smiley,showLabel:false,iconClass:this.iconClassPrefix+" "+this.iconClassPrefix+"Smiley",tabIndex:"-1",dropDown:this.dropDown});
},setEditor:function(_3){
this.editor=_3;
this._initButton();
},_preFilterEntities:function(s){
},_postFilterEntities:function(s){
}});
dojo.subscribe(dijit._scopeName+".Editor.getPlugin",null,function(o){
if(o.plugin){
return;
}
if(o.args.name==="smiley"){
o.plugin=new dojox.editor.plugins.Smiley();
}
});
}
