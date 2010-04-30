/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dojox.editor.plugins.TablePlugins"]){
dojo._hasResource["dojox.editor.plugins.TablePlugins"]=true;
dojo.provide("dojox.editor.plugins.TablePlugins");
dojo.require("dijit._editor._Plugin");
dojo.require("dijit._editor.selection");
dojo.require("dijit.Menu");
dojo.require("dojo.i18n");
dojo.requireLocalization("dojox.editor.plugins","TableDialog",null,"ROOT,ar,ca,cs,da,de,el,es,fi,fr,he,hu,it,ja,ko,nb,nl,pl,pt,pt-pt,ru,sk,sl,sv,th,tr,zh,zh-tw");
dojo.experimental("dojox.editor.plugins.TablePlugins");
dojo.declare("dojox.editor.plugins.GlobalTableHandler",dijit._editor._Plugin,{tablesConnected:false,currentlyAvailable:false,alwaysAvailable:false,availableCurrentlySet:false,initialized:false,tableData:null,shiftKeyDown:false,editorDomNode:null,undoEnabled:dojo.isIE,doMixins:function(){
dojo.mixin(this.editor,{getAncestorElement:function(_1){
return dojo.withGlobal(this.window,"getAncestorElement",dijit._editor.selection,[_1]);
},hasAncestorElement:function(_2){
return true;
return dojo.withGlobal(this.window,"hasAncestorElement",dijit._editor.selection,[_2]);
},selectElement:function(_3){
dojo.withGlobal(this.window,"selectElement",dijit._editor.selection,[_3]);
},byId:function(id){
return dojo.withGlobal(this.window,"byId",dojo,[id]);
},query:function(_4,_5,_6){
var ar=dojo.withGlobal(this.window,"query",dojo,[_4,_5]);
return (_6)?ar[0]:ar;
}});
},initialize:function(_7){
if(this.initialized){
return;
}
this.initialized=true;
this.editor=_7;
_7.onLoadDeferred.addCallback(dojo.hitch(this,function(){
this.editorDomNode=this.editor.editNode||this.editor.iframe.document.body.firstChild;
dojo.connect(this.editorDomNode,"mouseup",this.editor,"onClick");
dojo.connect(this.editor,"onDisplayChanged",this,"checkAvailable");
this.doMixins();
this.connectDraggable();
}));
},getTableInfo:function(_8){
if(_8){
this._tempStoreTableData(false);
}
if(this.tableData){
return this.tableData;
}
var tr,_9,td,_a,_b,_c,_d,_e;
td=this.editor.getAncestorElement("td");
if(td){
tr=td.parentNode;
}
_b=this.editor.getAncestorElement("table");
_a=dojo.query("td",_b);
_a.forEach(function(d,i){
if(td==d){
_d=i;
}
});
_9=dojo.query("tr",_b);
_9.forEach(function(r,i){
if(tr==r){
_e=i;
}
});
_c=_a.length/_9.length;
var o={tbl:_b,td:td,tr:tr,trs:_9,tds:_a,rows:_9.length,cols:_c,tdIndex:_d,trIndex:_e,colIndex:_d%_c};
this.tableData=o;
this._tempStoreTableData(500);
return this.tableData;
},connectDraggable:function(){
if(!dojo.isIE){
return;
}
this.editorDomNode.ondragstart=dojo.hitch(this,"onDragStart");
this.editorDomNode.ondragend=dojo.hitch(this,"onDragEnd");
},onDragStart:function(){
var e=window.event;
if(!e.srcElement.id){
e.srcElement.id="tbl_"+(new Date().getTime());
}
},onDragEnd:function(){
var e=window.event;
var _f=e.srcElement;
var id=_f.id;
var win=this.editor.window;
if(_f.tagName.toLowerCase()=="table"){
setTimeout(function(){
var _10=dojo.withGlobal(win,"byId",dojo,[id]);
dojo.removeAttr(_10,"align");
},100);
}
},checkAvailable:function(){
if(this.availableCurrentlySet){
return this.currentlyAvailable;
}
if(!this.editor){
return false;
}
if(this.alwaysAvailable){
return true;
}
this.currentlyAvailable=this.editor.hasAncestorElement("table");
if(this.currentlyAvailable){
this.connectTableKeys();
}else{
this.disconnectTableKeys();
}
this._tempAvailability(500);
dojo.publish("available",[this.currentlyAvailable]);
return this.currentlyAvailable;
},_prepareTable:function(tbl){
var tds=this.editor.query("td",tbl);
if(!tds[0].id){
tds.forEach(function(td,i){
if(!td.id){
td.id="tdid"+i+this.getTimeStamp();
}
},this);
}
return tds;
},getTimeStamp:function(){
return Math.floor(new Date().getTime()*1e-8);
},_tempStoreTableData:function(_11){
if(_11===true){
}else{
if(_11===false){
this.tableData=null;
}else{
if(_11===undefined){
console.warn("_tempStoreTableData must be passed an argument");
}else{
setTimeout(dojo.hitch(this,function(){
this.tableData=null;
}),_11);
}
}
}
},_tempAvailability:function(_12){
if(_12===true){
this.availableCurrentlySet=true;
}else{
if(_12===false){
this.availableCurrentlySet=false;
}else{
if(_12===undefined){
console.warn("_tempAvailability must be passed an argument");
}else{
this.availableCurrentlySet=true;
setTimeout(dojo.hitch(this,function(){
this.availableCurrentlySet=false;
}),_12);
}
}
}
},connectTableKeys:function(){
if(this.tablesConnected){
return;
}
this.tablesConnected=true;
var _13=(this.editor.iframe)?this.editor.document:this.editor.editNode;
this.cnKeyDn=dojo.connect(_13,"onkeydown",this,"onKeyDown");
this.cnKeyUp=dojo.connect(_13,"onkeyup",this,"onKeyUp");
dojo.connect(_13,"onkeypress",this,"onKeyUp");
},disconnectTableKeys:function(){
dojo.disconnect(this.cnKeyDn);
dojo.disconnect(this.cnKeyUp);
this.tablesConnected=false;
},onKeyDown:function(evt){
var key=evt.keyCode;
if(key==16){
this.shiftKeyDown=true;
}
if(key==9){
var o=this.getTableInfo();
o.tdIndex=(this.shiftKeyDown)?o.tdIndex-1:tabTo=o.tdIndex+1;
if(o.tdIndex>=0&&o.tdIndex<o.tds.length){
this.editor.selectElement(o.tds[o.tdIndex]);
this.currentlyAvailable=true;
this._tempAvailability(true);
this._tempStoreTableData(true);
this.stopEvent=true;
}else{
this.stopEvent=false;
this.onDisplayChanged();
}
if(this.stopEvent){
dojo.stopEvent(evt);
}
}
},onKeyUp:function(evt){
var key=evt.keyCode;
if(key==16){
this.shiftKeyDown=false;
}
if(key==37||key==38||key==39||key==40){
this.onDisplayChanged();
}
if(key==9&&this.stopEvent){
dojo.stopEvent(evt);
}
},onDisplayChanged:function(){
this.currentlyAvailable=false;
this._tempStoreTableData(false);
this._tempAvailability(false);
this.checkAvailable();
}});
tablePluginHandler=new dojox.editor.plugins.GlobalTableHandler();
dojo.declare("dojox.editor.plugins.TablePlugins",dijit._editor._Plugin,{iconClassPrefix:"editorIcon",useDefaultCommand:false,buttonClass:dijit.form.Button,commandName:"",label:"",alwaysAvailable:false,undoEnabled:false,constructor:function(){
switch(this.commandName){
case "colorTableCell":
this.buttonClass=dijit.form.DropDownButton;
this.dropDown=new dijit.ColorPalette();
this.connect(this.dropDown,"onChange",function(_14){
this.modTable(null,_14);
});
break;
case "modifyTable":
this.buttonClass=dijit.form.Button;
this.modTable=this.launchModifyDialog;
break;
case "insertTable":
this.alwaysAvailable=true;
this.buttonClass=dijit.form.Button;
this.modTable=this.launchInsertDialog;
break;
case "tableContextMenu":
this.connect(this,"setEditor",function(_15){
_15.onLoadDeferred.addCallback(dojo.hitch(this,function(){
this._createContextMenu();
}));
this.button.domNode.style.display="none";
});
break;
}
dojo.subscribe("available",this,"onDisplayChanged");
},onDisplayChanged:function(_16){
if(!this.alwaysAvailable){
this.available=_16;
this.button.attr("disabled",!this.available);
}
},setEditor:function(){
this.inherited(arguments);
this.onEditorLoaded();
},onEditorLoaded:function(){
tablePluginHandler.initialize(this.editor);
},_createContextMenu:function(){
var _17=dojo.isMoz?this.editor.editNode:this.editorDomNode;
var _18=new dijit.Menu({targetNodeIds:[_17],id:"progMenu",contextMenuForWindow:dojo.isIE});
var _19=dijit.MenuItem;
var _1a=dojo.i18n.getLocalization("dojox.editor.plugins","TableDialog",this.lang);
_18.addChild(new _19({label:_1a.selectTableLabel,onClick:dojo.hitch(this,"selectTable")}));
_18.addChild(new dijit.MenuSeparator());
_18.addChild(new _19({label:_1a.insertTableRowBeforeLabel,onClick:dojo.hitch(this,"modTable","insertTableRowBefore")}));
_18.addChild(new _19({label:_1a.insertTableRowAfterLabel,onClick:dojo.hitch(this,"modTable","insertTableRowAfter")}));
_18.addChild(new _19({label:_1a.insertTableColumnBeforeLabel,onClick:dojo.hitch(this,"modTable","insertTableColumnBefore")}));
_18.addChild(new _19({label:_1a.insertTableColumnAfterLabel,onClick:dojo.hitch(this,"modTable","insertTableColumnAfter")}));
_18.addChild(new dijit.MenuSeparator());
_18.addChild(new _19({label:_1a.deleteTableRowLabel,onClick:dojo.hitch(this,"modTable","deleteTableRow")}));
_18.addChild(new _19({label:_1a.deleteTableColumnLabel,onClick:dojo.hitch(this,"modTable","deleteTableColumn")}));
_18._openMyself=function(e){
if(!tablePluginHandler.checkAvailable()){
return;
}
if(this.leftClickToOpen&&e.button>0){
return;
}
dojo.stopEvent(e);
var x,y;
if(dojo.isIE){
x=e.x;
y=e.y;
}else{
x=e.screenX;
y=e.screenY+25;
}
var _1b=this;
var _1c=dijit.getFocus(this);
function _1d(){
dijit.focus(_1c);
dijit.popup.close(_1b);
};
var res=dijit.popup.open({popup:this,x:x,y:y,onExecute:_1d,onCancel:_1d,orient:this.isLeftToRight()?"L":"R"});
var v=dijit.getViewport();
if(res.y+res.h>v.h){
if(e.screenY-res.h>=0){
y=e.screenY-res.h;
}else{
y=0;
}
dijit.popup.close(this);
res=dijit.popup.open({popup:this,x:x,y:y,onExecute:_1d,onCancel:_1d,orient:this.isLeftToRight()?"L":"R"});
}
this.focus();
this._onBlur=function(){
this.inherited("_onBlur",arguments);
dijit.popup.close(this);
};
};
this.menu=_18;
},selectTable:function(){
var o=this.getTableInfo();
dojo.withGlobal(this.editor.window,"selectElement",dijit._editor.selection,[o.tbl]);
},launchInsertDialog:function(){
var w=new dojox.editor.plugins.EditorTableDialog({});
w.show();
var c=dojo.connect(w,"onBuildTable",this,function(obj){
dojo.disconnect(c);
var res=this.editor.execCommand("inserthtml",obj.htmlText);
});
},launchModifyDialog:function(){
var o=this.getTableInfo();
var w=new dojox.editor.plugins.EditorModifyTableDialog({table:o.tbl});
w.show();
this.connect(w,"onSetTable",function(_1e){
var o=this.getTableInfo();
dojo.attr(o.td,"bgcolor",_1e);
});
},_initButton:function(){
this.command=this.commandName;
this.label=this.editor.commands[this.command]=this._makeTitle(this.command);
this.inherited(arguments);
delete this.command;
if(this.commandName!="colorTableCell"){
this.connect(this.button.domNode,"click","modTable");
}
if(this.commandName=="tableContextMenu"){
this.button.domNode.display="none";
}
this.onDisplayChanged(false);
},modTable:function(cmd,_1f){
this.begEdit();
var o=this.getTableInfo();
var sw=(dojo.isString(cmd))?cmd:this.commandName;
var r,c,i;
var _20=false;
switch(sw){
case "insertTableRowBefore":
r=o.tbl.insertRow(o.trIndex);
for(i=0;i<o.cols;i++){
c=r.insertCell(-1);
c.innerHTML="&nbsp;";
}
break;
case "insertTableRowAfter":
r=o.tbl.insertRow(o.trIndex+1);
for(i=0;i<o.cols;i++){
c=r.insertCell(-1);
c.innerHTML="&nbsp;";
}
break;
case "insertTableColumnBefore":
o.trs.forEach(function(r){
c=r.insertCell(o.colIndex);
c.innerHTML="&nbsp;";
});
_20=true;
break;
case "insertTableColumnAfter":
o.trs.forEach(function(r){
c=r.insertCell(o.colIndex+1);
c.innerHTML="&nbsp;";
});
_20=true;
break;
case "deleteTableRow":
o.tbl.deleteRow(o.trIndex);
break;
case "deleteTableColumn":
o.trs.forEach(function(tr){
tr.deleteCell(o.colIndex);
});
_20=true;
break;
case "colorTableCell":
var tds=this.getSelectedCells(o.tbl);
dojo.forEach(tds,function(td){
dojo.style(td,"backgroundColor",_1f);
});
break;
case "modifyTable":
break;
case "insertTable":
break;
}
if(_20){
this.makeColumnsEven();
}
this.endEdit();
},begEdit:function(){
if(tablePluginHandler.undoEnabled){
if(this.editor.customUndo){
this.editor.beginEditing();
}else{
this.valBeforeUndo=this.editor.getValue();
}
}
},endEdit:function(){
if(tablePluginHandler.undoEnabled){
if(this.editor.customUndo){
this.editor.endEditing();
}else{
var _21=this.editor.getValue();
this.editor.setValue(this.valBeforeUndo);
this.editor.replaceValue(_21);
}
this.editor.onDisplayChanged();
}
},makeColumnsEven:function(){
setTimeout(dojo.hitch(this,function(){
var o=this.getTableInfo(true);
var w=Math.floor(100/o.cols);
o.tds.forEach(function(d){
dojo.attr(d,"width",w+"%");
});
}),10);
},getTableInfo:function(_22){
return tablePluginHandler.getTableInfo(_22);
},_makeTitle:function(str){
var s=str.split(""),ns=[];
dojo.forEach(str,function(c,i){
if(c.charCodeAt(0)<91&&i>0&&ns[i-1].charCodeAt(0)!=32){
ns.push(" ");
}
if(i==0){
c=c.toUpperCase();
}
ns.push(c);
});
return ns.join("");
},getSelectedCells:function(){
var _23=[];
var tbl=this.getTableInfo().tbl;
var tds=tablePluginHandler._prepareTable(tbl);
var e=this.editor;
var r;
if(!dojo.isIE){
r=dijit.range.getSelection(e.window);
var _24=false;
var _25=false;
if(r.anchorNode&&r.anchorNode.tagName&&r.anchorNode.tagName.toLowerCase()=="tr"){
var trs=dojo.query("tr",tbl);
var _26=[];
trs.forEach(function(tr,i){
if(!_24&&(tr==r.anchorNode||tr==r.focusNode)){
_26.push(tr);
_24=true;
if(r.anchorNode==r.focusNode){
_25=true;
}
}else{
if(_24&&!_25){
_26.push(tr);
if(tr==r.anchorNode||tr==r.focusNode){
_25=true;
}
}
}
});
dojo.forEach(_26,function(tr){
_23=_23.concat(dojo.query("td",tr));
},this);
}else{
tds.forEach(function(td,i){
if(!_24&&(td.id==r.anchorNode.parentNode.id||td.id==r.focusNode.parentNode.id)){
_23.push(td);
_24=true;
if(r.anchorNode.parentNode.id==r.focusNode.parentNode.id){
_25=true;
}
}else{
if(_24&&!_25){
_23.push(td);
if(td.id==r.focusNode.parentNode.id||td.id==r.anchorNode.parentNode.id){
_25=true;
}
}
}
});
}
}
if(dojo.isIE){
r=document.selection.createRange();
var str=r.htmlText.match(/id=\w*/g);
dojo.forEach(str,function(a){
var id=a.substring(3,a.length);
_23.push(e.byId(id));
},this);
}
return _23;
}});
dojo.provide("dojox.editor.plugins.EditorTableDialog");
dojo.require("dijit.Dialog");
dojo.require("dijit.form.TextBox");
dojo.require("dijit.form.FilteringSelect");
dojo.require("dijit.form.Button");
dojo.declare("dojox.editor.plugins.EditorTableDialog",[dijit.Dialog],{baseClass:"EditorTableDialog",widgetsInTemplate:true,templateString:dojo.cache("dojox.editor.plugins","resources/insertTable.html","<div class=\"dijitDialog\" tabindex=\"-1\" waiRole=\"dialog\" waiState=\"labelledby-${id}_title\">\n\t<div dojoAttachPoint=\"titleBar\" class=\"dijitDialogTitleBar\">\n\t<span dojoAttachPoint=\"titleNode\" class=\"dijitDialogTitle\" id=\"${id}_title\">${insertTableTitle}</span>\n\t<span dojoAttachPoint=\"closeButtonNode\" class=\"dijitDialogCloseIcon\" dojoAttachEvent=\"onclick: onCancel\" title=\"${buttonCancel}\">\n\t\t<span dojoAttachPoint=\"closeText\" class=\"closeText\" title=\"${buttonCancel}\">x</span>\n\t</span>\n\t</div>\n    <div dojoAttachPoint=\"containerNode\" class=\"dijitDialogPaneContent\">\n        <table class=\"etdTable\"><tr>\n            <td class=\"left\">\n                <span dojoAttachPoint=\"selectRow\" dojoType=\"dijit.form.TextBox\" value=\"2\"></span>\n                <label>${rows}</label>\n            </td><td class=\"right\">\n                <span dojoAttachPoint=\"selectCol\" dojoType=\"dijit.form.TextBox\" value=\"2\"></span>\n                <label>${columns}</label>\n            </td></tr><tr><td>\n                <span dojoAttachPoint=\"selectWidth\" dojoType=\"dijit.form.TextBox\" value=\"100\"></span>\n                <label>${tableWidth}</label>\n            </td><td>\n                <select dojoAttachPoint=\"selectWidthType\" hasDownArrow=\"true\" dojoType=\"dijit.form.FilteringSelect\">\n                  <option value=\"percent\">${percent}</option>\n                  <option value=\"pixels\">${pixels}</option>\n                </select></td></tr>\n          <tr><td>\n                <span dojoAttachPoint=\"selectBorder\" dojoType=\"dijit.form.TextBox\" value=\"1\"></span>\n                <label>${borderThickness}</label></td>\n            <td>\n                ${pixels}\n            </td></tr><tr><td>\n                <span dojoAttachPoint=\"selectPad\" dojoType=\"dijit.form.TextBox\" value=\"0\"></span>\n                <label>${cellPadding}</label></td>\n            <td class=\"cellpad\"></td></tr><tr><td>\n                <span dojoAttachPoint=\"selectSpace\" dojoType=\"dijit.form.TextBox\" value=\"0\"></span>\n                <label>${cellSpacing}</label>\n            </td><td class=\"cellspace\"></td></tr></table>\n        <div class=\"dialogButtonContainer\">\n            <div dojoType=\"dijit.form.Button\" dojoAttachEvent=\"onClick: onInsert\">${buttonInsert}</div>\n            <div dojoType=\"dijit.form.Button\" dojoAttachEvent=\"onClick: onCancel\">${buttonCancel}</div>\n        </div>\n\t</div>\n</div>\n"),postMixInProperties:function(){
var _27=dojo.i18n.getLocalization("dojox.editor.plugins","TableDialog",this.lang);
dojo.mixin(this,_27);
this.inherited(arguments);
},postCreate:function(){
dojo.addClass(this.domNode,this.baseClass);
this.inherited(arguments);
},onInsert:function(){
var _28=this.selectRow.attr("value")||1,_29=this.selectCol.attr("value")||1,_2a=this.selectWidth.attr("value"),_2b=this.selectWidthType.attr("value"),_2c=this.selectBorder.attr("value"),pad=this.selectPad.attr("value"),_2d=this.selectSpace.attr("value"),_2e="tbl_"+(new Date().getTime()),t="<table id=\""+_2e+"\"width=\""+_2a+((_2b=="percent")?"%":"")+"\" border=\""+_2c+"\" cellspacing=\""+_2d+"\" cellpadding=\""+pad+"\">\n";
for(var r=0;r<_28;r++){
t+="\t<tr>\n";
for(var c=0;c<_29;c++){
t+="\t\t<td width=\""+(Math.floor(100/_29))+"%\">&nbsp;</td>\n";
}
t+="\t</tr>\n";
}
t+="</table>";
this.onBuildTable({htmlText:t,id:_2e});
this.hide();
},onBuildTable:function(_2f){
}});
dojo.provide("dojox.editor.plugins.EditorModifyTableDialog");
dojo.require("dijit.ColorPalette");
dojo.declare("dojox.editor.plugins.EditorModifyTableDialog",[dijit.Dialog],{baseClass:"EditorTableDialog",widgetsInTemplate:true,table:null,tableAtts:{},templateString:dojo.cache("dojox.editor.plugins","resources/modifyTable.html","<div class=\"dijitDialog\" tabindex=\"-1\" waiRole=\"dialog\" waiState=\"labelledby-${id}_title\">\n\t<div dojoAttachPoint=\"titleBar\" class=\"dijitDialogTitleBar\">\n\t<span dojoAttachPoint=\"titleNode\" class=\"dijitDialogTitle\" id=\"${id}_title\">${modifyTableTitle}</span>\n\t<span dojoAttachPoint=\"closeButtonNode\" class=\"dijitDialogCloseIcon\" dojoAttachEvent=\"onclick: onCancel\" title=\"${buttonCancel}\">\n\t\t<span dojoAttachPoint=\"closeText\" class=\"closeText\" title=\"${buttonCancel}\">x</span>\n\t</span>\n\t</div>\n    <div dojoAttachPoint=\"containerNode\" class=\"dijitDialogPaneContent\">\n        <table class=\"etdTable\">\n          <tr><td class=\"left\">\n                <span class=\"colorSwatchBtn\" dojoAttachPoint=\"backgroundCol\"></span>\n                <label>${backgroundColor}</label>\n            </td><td class=\"right\">\n                <span class=\"colorSwatchBtn\" dojoAttachPoint=\"borderCol\"></span>\n                <label>${borderColor}</label>\n            </td></tr><tr><td>\n                <span dojoAttachPoint=\"selectBorder\" dojoType=\"dijit.form.TextBox\" value=\"1\"></span>\n                <label>${borderThickness}</label>\n            </td><td>\n            ${pixels}\n            </td></tr><tr><td>\n                <select class=\"floatDijit\" dojoAttachPoint=\"selectAlign\" dojoType=\"dijit.form.FilteringSelect\">\n                  <option value=\"default\">${default}</option>\n                  <option value=\"left\">${left}</option>\n                  <option value=\"center\">${center}</option>\n                  <option value=\"right\">${right}</option>\n                </select>\n                <label>${align}</label>\n            </td><td></td></tr><tr><td>\n                <span dojoAttachPoint=\"selectWidth\" dojoType=\"dijit.form.TextBox\" value=\"100\"></span>\n                <label>${tableWidth}</label>\n            </td><td>\n                <select dojoAttachPoint=\"selectWidthType\" hasDownArrow=\"true\" dojoType=\"dijit.form.FilteringSelect\">\n                  <option value=\"percent\">${percent}</option>\n                  <option value=\"pixels\">${pixels}</option>\n                </select>\n                </td></tr><tr><td>\n                <span dojoAttachPoint=\"selectPad\" dojoType=\"dijit.form.TextBox\" value=\"0\"></span>\n                <label>${cellPadding}</label></td>\n            <td class=\"cellpad\"></td></tr><tr><td>\n                <span dojoAttachPoint=\"selectSpace\" dojoType=\"dijit.form.TextBox\" value=\"0\"></span>\n                <label>${cellSpacing}</label>\n            </td><td class=\"cellspace\"></td></tr>\n        </table>\n        <div class=\"dialogButtonContainer\">\n            <div dojoType=\"dijit.form.Button\" dojoAttachEvent=\"onClick: onSet\">${buttonSet}</div>\n            <div dojoType=\"dijit.form.Button\" dojoAttachEvent=\"onClick: onCancel\">${buttonCancel}</div>\n        </div>\n\t</div>\n</div>\n"),postMixInProperties:function(){
var _30=dojo.i18n.getLocalization("dojox.editor.plugins","TableDialog",this.lang);
dojo.mixin(this,_30);
this.inherited(arguments);
},postCreate:function(){
dojo.addClass(this.domNode,this.baseClass);
this.inherited(arguments);
this.connect(this.borderCol,"click",function(){
var div=document.createElement("div");
var w=new dijit.ColorPalette({},div);
dijit.popup.open({popup:w,around:this.borderCol});
this.connect(w,"onChange",function(_31){
dijit.popup.close(w);
this.setBrdColor(_31);
});
});
this.connect(this.backgroundCol,"click",function(){
var div=document.createElement("div");
var w=new dijit.ColorPalette({},div);
dijit.popup.open({popup:w,around:this.backgroundCol});
this.connect(w,"onChange",function(_32){
dijit.popup.close(w);
this.setBkColor(_32);
});
});
this.setBrdColor(dojo.attr(this.table,"bordercolor"));
this.setBkColor(dojo.attr(this.table,"bgcolor"));
var w=dojo.attr(this.table,"width");
var p="pixels";
if(w.indexOf("%")>-1){
p="percent";
w=w.replace(/%/,"");
}
this.selectWidth.attr("value",w);
this.selectWidthType.attr("value",p);
this.selectBorder.attr("value",dojo.attr(this.table,"border"));
this.selectPad.attr("value",dojo.attr(this.table,"cellpadding"));
this.selectSpace.attr("value",dojo.attr(this.table,"cellspacing"));
this.selectAlign.attr("value",dojo.attr(this.table,"align"));
},setBrdColor:function(_33){
this.brdColor=_33;
dojo.style(this.borderCol,"backgroundColor",_33);
},setBkColor:function(_34){
this.bkColor=_34;
dojo.style(this.backgroundCol,"backgroundColor",_34);
},onSet:function(){
dojo.attr(this.table,"bordercolor",this.brdColor);
dojo.attr(this.table,"bgcolor",this.bkColor);
dojo.attr(this.table,"width",(this.selectWidth.attr("value")+((this.selectWidthType.attr("value")=="pixels")?"":"%")));
dojo.attr(this.table,"border",this.selectBorder.attr("value"));
dojo.attr(this.table,"cellpadding",this.selectPad.attr("value"));
dojo.attr(this.table,"cellspacing",this.selectSpace.attr("value"));
dojo.attr(this.table,"align",this.selectAlign.attr("value"));
this.hide();
},onSetTable:function(_35){
}});
dojo.subscribe(dijit._scopeName+".Editor.getPlugin",null,function(o){
if(o.plugin){
return;
}
if(o.args&&o.args.command){
var cmd=o.args.command.charAt(0).toLowerCase()+o.args.command.substring(1,o.args.command.length);
switch(cmd){
case "insertTableRowBefore":
case "insertTableRowAfter":
case "insertTableColumnBefore":
case "insertTableColumnAfter":
case "deleteTableRow":
case "deleteTableColumn":
case "colorTableCell":
case "modifyTable":
case "insertTable":
case "tableContextMenu":
o.plugin=new dojox.editor.plugins.TablePlugins({commandName:cmd});
break;
}
}
});
}
