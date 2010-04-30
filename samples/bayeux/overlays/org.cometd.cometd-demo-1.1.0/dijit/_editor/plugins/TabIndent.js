/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dijit._editor.plugins.TabIndent"]){
dojo._hasResource["dijit._editor.plugins.TabIndent"]=true;
dojo.provide("dijit._editor.plugins.TabIndent");
dojo.experimental("dijit._editor.plugins.TabIndent");
dojo.require("dijit._editor._Plugin");
dojo.require("dijit.form.ToggleButton");
dojo.declare("dijit._editor.plugins.TabIndent",dijit._editor._Plugin,{useDefaultCommand:false,buttonClass:dijit.form.ToggleButton,command:"tabIndent",_initButton:function(){
this.inherited(arguments);
var e=this.editor;
this.connect(this.button,"onChange",function(_1){
e.attr("isTabIndent",_1);
});
this.updateState();
},updateState:function(){
this.button.attr("checked",this.editor.isTabIndent,false);
}});
dojo.subscribe(dijit._scopeName+".Editor.getPlugin",null,function(o){
if(o.plugin){
return;
}
switch(o.args.name){
case "tabIndent":
o.plugin=new dijit._editor.plugins.TabIndent({command:o.args.name});
}
});
}
