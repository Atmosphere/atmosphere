/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dijit.form.ComboBox"]){
dojo._hasResource["dijit.form.ComboBox"]=true;
dojo.provide("dijit.form.ComboBox");
dojo.require("dijit.form._FormWidget");
dojo.require("dijit.form.ValidationTextBox");
dojo.require("dojo.data.util.simpleFetch");
dojo.require("dojo.data.util.filter");
dojo.require("dojo.regexp");
dojo.requireLocalization("dijit.form","ComboBox",null,"ROOT,ar,ca,cs,da,de,el,es,fi,fr,he,hu,it,ja,ko,nb,nl,pl,pt,pt-pt,ru,sk,sl,sv,th,tr,zh,zh-tw");
dojo.declare("dijit.form.ComboBoxMixin",null,{item:null,pageSize:Infinity,store:null,fetchProperties:{},query:{},autoComplete:true,highlightMatch:"first",searchDelay:100,searchAttr:"name",labelAttr:"",labelType:"text",queryExpr:"${0}*",ignoreCase:true,hasDownArrow:true,templateString:dojo.cache("dijit.form","templates/ComboBox.html","<div class=\"dijit dijitReset dijitInlineTable dijitLeft\"\n\tid=\"widget_${id}\"\n\tdojoAttachEvent=\"onmouseenter:_onMouse,onmouseleave:_onMouse,onmousedown:_onMouse\" dojoAttachPoint=\"comboNode\" waiRole=\"combobox\" tabIndex=\"-1\"\n\t><div style=\"overflow:hidden;\"\n\t\t><div class='dijitReset dijitRight dijitButtonNode dijitArrowButton dijitDownArrowButton'\n\t\t\tdojoAttachPoint=\"downArrowNode\" waiRole=\"presentation\"\n\t\t\tdojoAttachEvent=\"onmousedown:_onArrowMouseDown,onmouseup:_onMouse,onmouseenter:_onMouse,onmouseleave:_onMouse\"\n\t\t\t><div class=\"dijitArrowButtonInner\">&thinsp;</div\n\t\t\t><div class=\"dijitArrowButtonChar\">&#9660;</div\n\t\t></div\n\t\t><div class=\"dijitReset dijitValidationIcon\"><br></div\n\t\t><div class=\"dijitReset dijitValidationIconText\">&Chi;</div\n\t\t><div class=\"dijitReset dijitInputField\"\n\t\t\t><input ${nameAttrSetting} type=\"text\" autocomplete=\"off\" class='dijitReset'\n\t\t\tdojoAttachEvent=\"onkeypress:_onKeyPress,compositionend\"\n\t\t\tdojoAttachPoint=\"textbox,focusNode\" waiRole=\"textbox\" waiState=\"haspopup-true,autocomplete-list\"\n\t\t/></div\n\t></div\n></div>\n"),baseClass:"dijitComboBox",_getCaretPos:function(_1){
var _2=0;
if(typeof (_1.selectionStart)=="number"){
_2=_1.selectionStart;
}else{
if(dojo.isIE){
var tr=dojo.doc.selection.createRange().duplicate();
var _3=_1.createTextRange();
tr.move("character",0);
_3.move("character",0);
try{
_3.setEndPoint("EndToEnd",tr);
_2=String(_3.text).replace(/\r/g,"").length;
}
catch(e){
}
}
}
return _2;
},_setCaretPos:function(_4,_5){
_5=parseInt(_5);
dijit.selectInputText(_4,_5,_5);
},_setDisabledAttr:function(_6){
this.inherited(arguments);
dijit.setWaiState(this.comboNode,"disabled",_6);
},_abortQuery:function(){
if(this.searchTimer){
clearTimeout(this.searchTimer);
this.searchTimer=null;
}
if(this._fetchHandle){
if(this._fetchHandle.abort){
this._fetchHandle.abort();
}
this._fetchHandle=null;
}
},_onKeyPress:function(_7){
var _8=_7.charOrCode;
if(_7.altKey||((_7.ctrlKey||_7.metaKey)&&(_8!="x"&&_8!="v"))||_8==dojo.keys.SHIFT){
return;
}
var _9=false;
var _a="_startSearchFromInput";
var pw=this._popupWidget;
var dk=dojo.keys;
var _b=null;
this._prev_key_backspace=false;
this._abortQuery();
if(this._isShowingNow){
pw.handleKey(_8);
_b=pw.getHighlightedOption();
}
switch(_8){
case dk.PAGE_DOWN:
case dk.DOWN_ARROW:
case dk.PAGE_UP:
case dk.UP_ARROW:
if(!this._isShowingNow){
this._arrowPressed();
_9=true;
_a="_startSearchAll";
}else{
this._announceOption(_b);
}
dojo.stopEvent(_7);
break;
case dk.ENTER:
if(_b){
if(_b==pw.nextButton){
this._nextSearch(1);
dojo.stopEvent(_7);
break;
}else{
if(_b==pw.previousButton){
this._nextSearch(-1);
dojo.stopEvent(_7);
break;
}
}
}else{
this._setBlurValue();
this._setCaretPos(this.focusNode,this.focusNode.value.length);
}
_7.preventDefault();
case dk.TAB:
var _c=this.attr("displayedValue");
if(pw&&(_c==pw._messages["previousMessage"]||_c==pw._messages["nextMessage"])){
break;
}
if(_b){
this._selectOption();
}
if(this._isShowingNow){
this._lastQuery=null;
this._hideResultList();
}
break;
case " ":
if(_b){
dojo.stopEvent(_7);
this._selectOption();
this._hideResultList();
}else{
_9=true;
}
break;
case dk.ESCAPE:
if(this._isShowingNow){
dojo.stopEvent(_7);
this._hideResultList();
}
break;
case dk.DELETE:
case dk.BACKSPACE:
this._prev_key_backspace=true;
_9=true;
break;
default:
_9=typeof _8=="string"||_8==229;
}
if(_9){
this.item=undefined;
this.searchTimer=setTimeout(dojo.hitch(this,_a),1);
}
},_autoCompleteText:function(_d){
var fn=this.focusNode;
dijit.selectInputText(fn,fn.value.length);
var _e=this.ignoreCase?"toLowerCase":"substr";
if(_d[_e](0).indexOf(this.focusNode.value[_e](0))==0){
var _f=this._getCaretPos(fn);
if((_f+1)>fn.value.length){
fn.value=_d;
dijit.selectInputText(fn,_f);
}
}else{
fn.value=_d;
dijit.selectInputText(fn);
}
},_openResultList:function(_10,_11){
this._fetchHandle=null;
if(this.disabled||this.readOnly||(_11.query[this.searchAttr]!=this._lastQuery)){
return;
}
this._popupWidget.clearResultList();
if(!_10.length){
this._hideResultList();
return;
}
_11._maxOptions=this._maxOptions;
var _12=this._popupWidget.createOptions(_10,_11,dojo.hitch(this,"_getMenuLabelFromItem"));
this._showResultList();
if(_11.direction){
if(1==_11.direction){
this._popupWidget.highlightFirstOption();
}else{
if(-1==_11.direction){
this._popupWidget.highlightLastOption();
}
}
this._announceOption(this._popupWidget.getHighlightedOption());
}else{
if(this.autoComplete&&!this._prev_key_backspace&&!/^[*]+$/.test(_11.query[this.searchAttr])){
this._announceOption(_12[1]);
}
}
},_showResultList:function(){
this._hideResultList();
this._arrowPressed();
this.displayMessage("");
dojo.style(this._popupWidget.domNode,{width:"",height:""});
var _13=this.open();
var _14=dojo.marginBox(this._popupWidget.domNode);
this._popupWidget.domNode.style.overflow=((_13.h==_14.h)&&(_13.w==_14.w))?"hidden":"auto";
var _15=_13.w;
if(_13.h<this._popupWidget.domNode.scrollHeight){
_15+=16;
}
dojo.marginBox(this._popupWidget.domNode,{h:_13.h,w:Math.max(_15,this.domNode.offsetWidth)});
if(_15<this.domNode.offsetWidth){
this._popupWidget.domNode.parentNode.style.left=dojo.position(this.domNode).x+"px";
}
dijit.setWaiState(this.comboNode,"expanded","true");
},_hideResultList:function(){
this._abortQuery();
if(this._isShowingNow){
dijit.popup.close(this._popupWidget);
this._arrowIdle();
this._isShowingNow=false;
dijit.setWaiState(this.comboNode,"expanded","false");
dijit.removeWaiState(this.focusNode,"activedescendant");
}
},_setBlurValue:function(){
var _16=this.attr("displayedValue");
var pw=this._popupWidget;
if(pw&&(_16==pw._messages["previousMessage"]||_16==pw._messages["nextMessage"])){
this._setValueAttr(this._lastValueReported,true);
}else{
if(typeof this.item=="undefined"){
this.item=null;
this.attr("displayedValue",_16);
}else{
if(this.value!=this._lastValueReported){
dijit.form._FormValueWidget.prototype._setValueAttr.call(this,this.value,true);
}
this._refreshState();
}
}
},_onBlur:function(){
this._hideResultList();
this._arrowIdle();
this.inherited(arguments);
},_setItemAttr:function(_17,_18,_19){
if(!_19){
_19=this.labelFunc(_17,this.store);
}
this.value=this._getValueField()!=this.searchAttr?this.store.getIdentity(_17):_19;
this.item=_17;
dijit.form.ComboBox.superclass._setValueAttr.call(this,this.value,_18,_19);
},_announceOption:function(_1a){
if(!_1a){
return;
}
var _1b;
if(_1a==this._popupWidget.nextButton||_1a==this._popupWidget.previousButton){
_1b=_1a.innerHTML;
this.item=undefined;
this.value="";
}else{
_1b=this.labelFunc(_1a.item,this.store);
this.attr("item",_1a.item,false,_1b);
}
this.focusNode.value=this.focusNode.value.substring(0,this._lastInput.length);
dijit.setWaiState(this.focusNode,"activedescendant",dojo.attr(_1a,"id"));
this._autoCompleteText(_1b);
},_selectOption:function(evt){
if(evt){
this._announceOption(evt.target);
}
this._hideResultList();
this._setCaretPos(this.focusNode,this.focusNode.value.length);
dijit.form._FormValueWidget.prototype._setValueAttr.call(this,this.value,true);
},_onArrowMouseDown:function(evt){
if(this.disabled||this.readOnly){
return;
}
dojo.stopEvent(evt);
this.focus();
if(this._isShowingNow){
this._hideResultList();
}else{
this._startSearchAll();
}
},_startSearchAll:function(){
this._startSearch("");
},_startSearchFromInput:function(){
this._startSearch(this.focusNode.value.replace(/([\\\*\?])/g,"\\$1"));
},_getQueryString:function(_1c){
return dojo.string.substitute(this.queryExpr,[_1c]);
},_startSearch:function(key){
if(!this._popupWidget){
var _1d=this.id+"_popup";
this._popupWidget=new dijit.form._ComboBoxMenu({onChange:dojo.hitch(this,this._selectOption),id:_1d});
dijit.removeWaiState(this.focusNode,"activedescendant");
dijit.setWaiState(this.textbox,"owns",_1d);
}
var _1e=dojo.clone(this.query);
this._lastInput=key;
this._lastQuery=_1e[this.searchAttr]=this._getQueryString(key);
this.searchTimer=setTimeout(dojo.hitch(this,function(_1f,_20){
this.searchTimer=null;
var _21={queryOptions:{ignoreCase:this.ignoreCase,deep:true},query:_1f,onBegin:dojo.hitch(this,"_setMaxOptions"),onComplete:dojo.hitch(this,"_openResultList"),onError:function(_22){
_20._fetchHandle=null;
console.error("dijit.form.ComboBox: "+_22);
dojo.hitch(_20,"_hideResultList")();
},start:0,count:this.pageSize};
dojo.mixin(_21,_20.fetchProperties);
this._fetchHandle=_20.store.fetch(_21);
var _23=function(_24,_25){
_24.start+=_24.count*_25;
_24.direction=_25;
this._fetchHandle=this.store.fetch(_24);
};
this._nextSearch=this._popupWidget.onPage=dojo.hitch(this,_23,this._fetchHandle);
},_1e,this),this.searchDelay);
},_setMaxOptions:function(_26,_27){
this._maxOptions=_26;
},_getValueField:function(){
return this.searchAttr;
},_arrowPressed:function(){
if(!this.disabled&&!this.readOnly&&this.hasDownArrow){
dojo.addClass(this.downArrowNode,"dijitArrowButtonActive");
}
},_arrowIdle:function(){
if(!this.disabled&&!this.readOnly&&this.hasDownArrow){
dojo.removeClass(this.downArrowNode,"dojoArrowButtonPushed");
}
},compositionend:function(evt){
this._onKeyPress({charOrCode:229});
},constructor:function(){
this.query={};
this.fetchProperties={};
},postMixInProperties:function(){
if(!this.hasDownArrow){
this.baseClass="dijitTextBox";
}
if(!this.store){
var _28=this.srcNodeRef;
this.store=new dijit.form._ComboBoxDataStore(_28);
if(!this.value||((typeof _28.selectedIndex=="number")&&_28.selectedIndex.toString()===this.value)){
var _29=this.store.fetchSelectedItem();
if(_29){
var _2a=this._getValueField();
this.value=_2a!=this.searchAttr?this.store.getValue(_29,_2a):this.labelFunc(_29,this.store);
}
}
}
this.inherited(arguments);
},postCreate:function(){
var _2b=dojo.query("label[for=\""+this.id+"\"]");
if(_2b.length){
_2b[0].id=(this.id+"_label");
var cn=this.comboNode;
dijit.setWaiState(cn,"labelledby",_2b[0].id);
}
this.inherited(arguments);
},uninitialize:function(){
if(this._popupWidget&&!this._popupWidget._destroyed){
this._hideResultList();
this._popupWidget.destroy();
}
this.inherited(arguments);
},_getMenuLabelFromItem:function(_2c){
var _2d=this.labelAttr?this.store.getValue(_2c,this.labelAttr):this.labelFunc(_2c,this.store);
var _2e=this.labelType;
if(this.highlightMatch!="none"&&this.labelType=="text"&&this._lastInput){
_2d=this.doHighlight(_2d,this._escapeHtml(this._lastInput));
_2e="html";
}
return {html:_2e=="html",label:_2d};
},doHighlight:function(_2f,_30){
var _31="i"+(this.highlightMatch=="all"?"g":"");
var _32=this._escapeHtml(_2f);
_30=dojo.regexp.escapeString(_30);
var ret=_32.replace(new RegExp("(^|\\s)("+_30+")",_31),"$1<span class=\"dijitComboBoxHighlightMatch\">$2</span>");
return ret;
},_escapeHtml:function(str){
str=String(str).replace(/&/gm,"&amp;").replace(/</gm,"&lt;").replace(/>/gm,"&gt;").replace(/"/gm,"&quot;");
return str;
},open:function(){
this._isShowingNow=true;
return dijit.popup.open({popup:this._popupWidget,around:this.domNode,parent:this});
},reset:function(){
this.item=null;
this.inherited(arguments);
},labelFunc:function(_33,_34){
return _34.getValue(_33,this.searchAttr).toString();
}});
dojo.declare("dijit.form._ComboBoxMenu",[dijit._Widget,dijit._Templated],{templateString:"<ul class='dijitReset dijitMenu' dojoAttachEvent='onmousedown:_onMouseDown,onmouseup:_onMouseUp,onmouseover:_onMouseOver,onmouseout:_onMouseOut' tabIndex='-1' style='overflow: \"auto\"; overflow-x: \"hidden\";'>"+"<li class='dijitMenuItem dijitMenuPreviousButton' dojoAttachPoint='previousButton' waiRole='option'></li>"+"<li class='dijitMenuItem dijitMenuNextButton' dojoAttachPoint='nextButton' waiRole='option'></li>"+"</ul>",_messages:null,postMixInProperties:function(){
this._messages=dojo.i18n.getLocalization("dijit.form","ComboBox",this.lang);
this.inherited(arguments);
},_setValueAttr:function(_35){
this.value=_35;
this.onChange(_35);
},onChange:function(_36){
},onPage:function(_37){
},postCreate:function(){
this.previousButton.innerHTML=this._messages["previousMessage"];
this.nextButton.innerHTML=this._messages["nextMessage"];
this.inherited(arguments);
},onClose:function(){
this._blurOptionNode();
},_createOption:function(_38,_39){
var _3a=_39(_38);
var _3b=dojo.doc.createElement("li");
dijit.setWaiRole(_3b,"option");
if(_3a.html){
_3b.innerHTML=_3a.label;
}else{
_3b.appendChild(dojo.doc.createTextNode(_3a.label));
}
if(_3b.innerHTML==""){
_3b.innerHTML="&nbsp;";
}
_3b.item=_38;
return _3b;
},createOptions:function(_3c,_3d,_3e){
this.previousButton.style.display=(_3d.start==0)?"none":"";
dojo.attr(this.previousButton,"id",this.id+"_prev");
dojo.forEach(_3c,function(_3f,i){
var _40=this._createOption(_3f,_3e);
_40.className="dijitReset dijitMenuItem";
dojo.attr(_40,"id",this.id+i);
this.domNode.insertBefore(_40,this.nextButton);
},this);
var _41=false;
if(_3d._maxOptions&&_3d._maxOptions!=-1){
if((_3d.start+_3d.count)<_3d._maxOptions){
_41=true;
}else{
if((_3d.start+_3d.count)>(_3d._maxOptions-1)){
if(_3d.count==_3c.length){
_41=true;
}
}
}
}else{
if(_3d.count==_3c.length){
_41=true;
}
}
this.nextButton.style.display=_41?"":"none";
dojo.attr(this.nextButton,"id",this.id+"_next");
return this.domNode.childNodes;
},clearResultList:function(){
while(this.domNode.childNodes.length>2){
this.domNode.removeChild(this.domNode.childNodes[this.domNode.childNodes.length-2]);
}
},_onMouseDown:function(evt){
dojo.stopEvent(evt);
},_onMouseUp:function(evt){
if(evt.target===this.domNode){
return;
}else{
if(evt.target==this.previousButton){
this.onPage(-1);
}else{
if(evt.target==this.nextButton){
this.onPage(1);
}else{
var tgt=evt.target;
while(!tgt.item){
tgt=tgt.parentNode;
}
this._setValueAttr({target:tgt},true);
}
}
}
},_onMouseOver:function(evt){
if(evt.target===this.domNode){
return;
}
var tgt=evt.target;
if(!(tgt==this.previousButton||tgt==this.nextButton)){
while(!tgt.item){
tgt=tgt.parentNode;
}
}
this._focusOptionNode(tgt);
},_onMouseOut:function(evt){
if(evt.target===this.domNode){
return;
}
this._blurOptionNode();
},_focusOptionNode:function(_42){
if(this._highlighted_option!=_42){
this._blurOptionNode();
this._highlighted_option=_42;
dojo.addClass(this._highlighted_option,"dijitMenuItemSelected");
}
},_blurOptionNode:function(){
if(this._highlighted_option){
dojo.removeClass(this._highlighted_option,"dijitMenuItemSelected");
this._highlighted_option=null;
}
},_highlightNextOption:function(){
var fc=this.domNode.firstChild;
if(!this.getHighlightedOption()){
this._focusOptionNode(fc.style.display=="none"?fc.nextSibling:fc);
}else{
var ns=this._highlighted_option.nextSibling;
if(ns&&ns.style.display!="none"){
this._focusOptionNode(ns);
}
}
dijit.scrollIntoView(this._highlighted_option);
},highlightFirstOption:function(){
this._focusOptionNode(this.domNode.firstChild.nextSibling);
dijit.scrollIntoView(this._highlighted_option);
},highlightLastOption:function(){
this._focusOptionNode(this.domNode.lastChild.previousSibling);
dijit.scrollIntoView(this._highlighted_option);
},_highlightPrevOption:function(){
var lc=this.domNode.lastChild;
if(!this.getHighlightedOption()){
this._focusOptionNode(lc.style.display=="none"?lc.previousSibling:lc);
}else{
var ps=this._highlighted_option.previousSibling;
if(ps&&ps.style.display!="none"){
this._focusOptionNode(ps);
}
}
dijit.scrollIntoView(this._highlighted_option);
},_page:function(up){
var _43=0;
var _44=this.domNode.scrollTop;
var _45=dojo.style(this.domNode,"height");
if(!this.getHighlightedOption()){
this._highlightNextOption();
}
while(_43<_45){
if(up){
if(!this.getHighlightedOption().previousSibling||this._highlighted_option.previousSibling.style.display=="none"){
break;
}
this._highlightPrevOption();
}else{
if(!this.getHighlightedOption().nextSibling||this._highlighted_option.nextSibling.style.display=="none"){
break;
}
this._highlightNextOption();
}
var _46=this.domNode.scrollTop;
_43+=(_46-_44)*(up?-1:1);
_44=_46;
}
},pageUp:function(){
this._page(true);
},pageDown:function(){
this._page(false);
},getHighlightedOption:function(){
var ho=this._highlighted_option;
return (ho&&ho.parentNode)?ho:null;
},handleKey:function(key){
switch(key){
case dojo.keys.DOWN_ARROW:
this._highlightNextOption();
break;
case dojo.keys.PAGE_DOWN:
this.pageDown();
break;
case dojo.keys.UP_ARROW:
this._highlightPrevOption();
break;
case dojo.keys.PAGE_UP:
this.pageUp();
break;
}
}});
dojo.declare("dijit.form.ComboBox",[dijit.form.ValidationTextBox,dijit.form.ComboBoxMixin],{_setValueAttr:function(_47,_48,_49){
this.item=null;
if(!_47){
_47="";
}
dijit.form.ValidationTextBox.prototype._setValueAttr.call(this,_47,_48,_49);
}});
dojo.declare("dijit.form._ComboBoxDataStore",null,{constructor:function(_4a){
this.root=_4a;
dojo.query("> option",_4a).forEach(function(_4b){
_4b.innerHTML=dojo.trim(_4b.innerHTML);
});
},getValue:function(_4c,_4d,_4e){
return (_4d=="value")?_4c.value:(_4c.innerText||_4c.textContent||"");
},isItemLoaded:function(_4f){
return true;
},getFeatures:function(){
return {"dojo.data.api.Read":true,"dojo.data.api.Identity":true};
},_fetchItems:function(_50,_51,_52){
if(!_50.query){
_50.query={};
}
if(!_50.query.name){
_50.query.name="";
}
if(!_50.queryOptions){
_50.queryOptions={};
}
var _53=dojo.data.util.filter.patternToRegExp(_50.query.name,_50.queryOptions.ignoreCase),_54=dojo.query("> option",this.root).filter(function(_55){
return (_55.innerText||_55.textContent||"").match(_53);
});
if(_50.sort){
_54.sort(dojo.data.util.sorter.createSortFunction(_50.sort,this));
}
_51(_54,_50);
},close:function(_56){
return;
},getLabel:function(_57){
return _57.innerHTML;
},getIdentity:function(_58){
return dojo.attr(_58,"value");
},fetchItemByIdentity:function(_59){
var _5a=dojo.query("option[value='"+_59.identity+"']",this.root)[0];
_59.onItem(_5a);
},fetchSelectedItem:function(){
var _5b=this.root,si=_5b.selectedIndex;
return dojo.query("> option:nth-child("+(si!=-1?si+1:1)+")",_5b)[0];
}});
dojo.extend(dijit.form._ComboBoxDataStore,dojo.data.util.simpleFetch);
}
