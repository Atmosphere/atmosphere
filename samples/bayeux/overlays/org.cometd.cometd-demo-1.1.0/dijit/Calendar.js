/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dijit.Calendar"]){
dojo._hasResource["dijit.Calendar"]=true;
dojo.provide("dijit.Calendar");
dojo.require("dojo.cldr.supplemental");
dojo.require("dojo.date");
dojo.require("dojo.date.locale");
dojo.require("dijit._Widget");
dojo.require("dijit._Templated");
dojo.declare("dijit.Calendar",[dijit._Widget,dijit._Templated],{templateString:dojo.cache("dijit","templates/Calendar.html","<table cellspacing=\"0\" cellpadding=\"0\" class=\"dijitCalendarContainer\" role=\"grid\" dojoAttachEvent=\"onkeypress: _onKeyPress\">\n\t<thead>\n\t\t<tr class=\"dijitReset dijitCalendarMonthContainer\" valign=\"top\">\n\t\t\t<th class='dijitReset' dojoAttachPoint=\"decrementMonth\">\n\t\t\t\t<img src=\"${_blankGif}\" alt=\"\" class=\"dijitCalendarIncrementControl dijitCalendarDecrease\" waiRole=\"presentation\">\n\t\t\t\t<span dojoAttachPoint=\"decreaseArrowNode\" class=\"dijitA11ySideArrow\">-</span>\n\t\t\t</th>\n\t\t\t<th class='dijitReset' colspan=\"5\">\n\t\t\t\t<div class=\"dijitVisible\">\n\t\t\t\t\t<div class=\"dijitPopup dijitMenu dijitMenuPassive dijitHidden\" dojoAttachPoint=\"monthDropDown\" dojoAttachEvent=\"onmouseup: _onMonthSelect, onmouseover: _onMenuHover, onmouseout: _onMenuHover\">\n\t\t\t\t\t\t<div class=\"dijitCalendarMonthLabelTemplate dijitCalendarMonthLabel\"></div>\n\t\t\t\t\t</div>\n\t\t\t\t</div>\n\t\t\t\t<div dojoAttachPoint=\"monthLabelSpacer\" class=\"dijitSpacer\"></div>\n\t\t\t\t<div dojoAttachPoint=\"monthLabelNode\" class=\"dijitCalendarMonthLabel dijitInline dijitVisible\" dojoAttachEvent=\"onmousedown: _onMonthToggle\"></div>\n\t\t\t</th>\n\t\t\t<th class='dijitReset' dojoAttachPoint=\"incrementMonth\">\n\t\t\t\t<img src=\"${_blankGif}\" alt=\"\" class=\"dijitCalendarIncrementControl dijitCalendarIncrease\" waiRole=\"presentation\">\n\t\t\t\t<span dojoAttachPoint=\"increaseArrowNode\" class=\"dijitA11ySideArrow\">+</span>\n\t\t\t</th>\n\t\t</tr>\n\t\t<tr>\n\t\t\t<th class=\"dijitReset dijitCalendarDayLabelTemplate\" role=\"columnheader\"><span class=\"dijitCalendarDayLabel\"></span></th>\n\t\t</tr>\n\t</thead>\n\t<tbody dojoAttachEvent=\"onclick: _onDayClick, onmouseover: _onDayMouseOver, onmouseout: _onDayMouseOut\" class=\"dijitReset dijitCalendarBodyContainer\">\n\t\t<tr class=\"dijitReset dijitCalendarWeekTemplate\" role=\"row\">\n\t\t\t<td class=\"dijitReset dijitCalendarDateTemplate\" role=\"gridcell\"><span class=\"dijitCalendarDateLabel\"></span></td>\n\t\t</tr>\n\t</tbody>\n\t<tfoot class=\"dijitReset dijitCalendarYearContainer\">\n\t\t<tr>\n\t\t\t<td class='dijitReset' valign=\"top\" colspan=\"7\">\n\t\t\t\t<h3 class=\"dijitCalendarYearLabel\">\n\t\t\t\t\t<span dojoAttachPoint=\"previousYearLabelNode\" class=\"dijitInline dijitCalendarPreviousYear\"></span>\n\t\t\t\t\t<span dojoAttachPoint=\"currentYearLabelNode\" class=\"dijitInline dijitCalendarSelectedYear\"></span>\n\t\t\t\t\t<span dojoAttachPoint=\"nextYearLabelNode\" class=\"dijitInline dijitCalendarNextYear\"></span>\n\t\t\t\t</h3>\n\t\t\t</td>\n\t\t</tr>\n\t</tfoot>\n</table>\n"),value:new Date(),datePackage:"dojo.date",dayWidth:"narrow",tabIndex:"0",attributeMap:dojo.delegate(dijit._Widget.prototype.attributeMap,{tabIndex:"domNode"}),setValue:function(_1){
dojo.deprecated("dijit.Calendar:setValue() is deprecated.  Use attr('value', ...) instead.","","2.0");
this.attr("value",_1);
},_getValueAttr:function(){
var _2=new this.dateClassObj(this.value);
_2.setHours(0,0,0,0);
if(_2.getDate()<this.value.getDate()){
_2=this.dateFuncObj.add(_2,"hour",1);
}
return _2;
},_setValueAttr:function(_3){
if(!this.value||this.dateFuncObj.compare(_3,this.value)){
_3=new this.dateClassObj(_3);
_3.setHours(1);
this.displayMonth=new this.dateClassObj(_3);
if(!this.isDisabledDate(_3,this.lang)){
this.value=_3;
this.onChange(this.attr("value"));
}
dojo.attr(this.domNode,"aria-label",this.dateLocaleModule.format(_3,{selector:"date",formatLength:"full"}));
this._populateGrid();
}
},_setText:function(_4,_5){
while(_4.firstChild){
_4.removeChild(_4.firstChild);
}
_4.appendChild(dojo.doc.createTextNode(_5));
},_populateGrid:function(){
var _6=this.displayMonth;
_6.setDate(1);
var _7=_6.getDay(),_8=this.dateFuncObj.getDaysInMonth(_6),_9=this.dateFuncObj.getDaysInMonth(this.dateFuncObj.add(_6,"month",-1)),_a=new this.dateClassObj(),_b=dojo.cldr.supplemental.getFirstDayOfWeek(this.lang);
if(_b>_7){
_b-=7;
}
dojo.query(".dijitCalendarDateTemplate",this.domNode).forEach(function(_c,i){
i+=_b;
var _d=new this.dateClassObj(_6),_e,_f="dijitCalendar",adj=0;
if(i<_7){
_e=_9-_7+i+1;
adj=-1;
_f+="Previous";
}else{
if(i>=(_7+_8)){
_e=i-_7-_8+1;
adj=1;
_f+="Next";
}else{
_e=i-_7+1;
_f+="Current";
}
}
if(adj){
_d=this.dateFuncObj.add(_d,"month",adj);
}
_d.setDate(_e);
if(!this.dateFuncObj.compare(_d,_a,"date")){
_f="dijitCalendarCurrentDate "+_f;
}
if(this._isSelectedDate(_d,this.lang)){
_f="dijitCalendarSelectedDate "+_f;
}
if(this.isDisabledDate(_d,this.lang)){
_f="dijitCalendarDisabledDate "+_f;
}
var _10=this.getClassForDate(_d,this.lang);
if(_10){
_f=_10+" "+_f;
}
_c.className=_f+"Month dijitCalendarDateTemplate";
_c.dijitDateValue=_d.valueOf();
var _11=dojo.query(".dijitCalendarDateLabel",_c)[0],_12=_d.getDateLocalized?_d.getDateLocalized(this.lang):_d.getDate();
this._setText(_11,_12);
},this);
var _13=this.dateLocaleModule.getNames("months","wide","standAlone",this.lang);
this._setText(this.monthLabelNode,_13[_6.getMonth()]);
var y=_6.getFullYear()-1;
var d=new this.dateClassObj();
dojo.forEach(["previous","current","next"],function(_14){
d.setFullYear(y++);
this._setText(this[_14+"YearLabelNode"],this.dateLocaleModule.format(d,{selector:"year",locale:this.lang}));
},this);
var _15=this;
var _16=function(_17,_18,adj){
_15._connects.push(dijit.typematic.addMouseListener(_15[_17],_15,function(_19){
if(_19>=0){
_15._adjustDisplay(_18,adj);
}
},0.8,500));
};
_16("incrementMonth","month",1);
_16("decrementMonth","month",-1);
_16("nextYearLabelNode","year",1);
_16("previousYearLabelNode","year",-1);
},goToToday:function(){
this.attr("value",this.dateClassObj());
},constructor:function(_1a){
var _1b=(_1a.datePackage&&(_1a.datePackage!="dojo.date"))?_1a.datePackage+".Date":"Date";
this.dateClassObj=dojo.getObject(_1b,false);
this.datePackage=_1a.datePackage||this.datePackage;
this.dateFuncObj=dojo.getObject(this.datePackage,false);
this.dateLocaleModule=dojo.getObject(this.datePackage+".locale",false);
},postMixInProperties:function(){
if(isNaN(this.value)){
delete this.value;
}
this.inherited(arguments);
},postCreate:function(){
this.inherited(arguments);
dojo.setSelectable(this.domNode,false);
var _1c=dojo.hitch(this,function(_1d,n){
var _1e=dojo.query(_1d,this.domNode)[0];
for(var i=0;i<n;i++){
_1e.parentNode.appendChild(_1e.cloneNode(true));
}
});
_1c(".dijitCalendarDayLabelTemplate",6);
_1c(".dijitCalendarDateTemplate",6);
_1c(".dijitCalendarWeekTemplate",5);
var _1f=this.dateLocaleModule.getNames("days",this.dayWidth,"standAlone",this.lang);
var _20=dojo.cldr.supplemental.getFirstDayOfWeek(this.lang);
dojo.query(".dijitCalendarDayLabel",this.domNode).forEach(function(_21,i){
this._setText(_21,_1f[(i+_20)%7]);
},this);
var _22=this.dateLocaleModule.getNames("months","wide","standAlone",this.lang);
_1c(".dijitCalendarMonthLabelTemplate",_22.length-1);
dojo.query(".dijitCalendarMonthLabelTemplate",this.domNode).forEach(function(_23,i){
dojo.attr(_23,"month",i);
this._setText(_23,_22[i]);
dojo.place(_23.cloneNode(true),this.monthLabelSpacer);
},this);
var _24=this.value;
this.value=null;
this.attr("value",new this.dateClassObj(_24));
},_onMenuHover:function(e){
dojo.stopEvent(e);
dojo.toggleClass(e.target,"dijitMenuItemHover");
},_adjustDisplay:function(_25,_26){
this.displayMonth=this.dateFuncObj.add(this.displayMonth,_25,_26);
this._populateGrid();
},_onMonthToggle:function(evt){
dojo.stopEvent(evt);
if(evt.type=="mousedown"){
var _27=dojo.position(this.monthLabelNode);
var dim={width:_27.w+"px",top:-this.displayMonth.getMonth()*_27.h+"px"};
if((dojo.isIE&&dojo.isQuirks)||dojo.isIE<7){
dim.left=-_27.w/2+"px";
}
dojo.style(this.monthDropDown,dim);
this._popupHandler=this.connect(document,"onmouseup","_onMonthToggle");
}else{
this.disconnect(this._popupHandler);
delete this._popupHandler;
}
dojo.toggleClass(this.monthDropDown,"dijitHidden");
dojo.toggleClass(this.monthLabelNode,"dijitVisible");
},_onMonthSelect:function(evt){
this._onMonthToggle(evt);
this.displayMonth.setMonth(dojo.attr(evt.target,"month"));
this._populateGrid();
},_onDayClick:function(evt){
dojo.stopEvent(evt);
for(var _28=evt.target;_28&&!_28.dijitDateValue;_28=_28.parentNode){
}
if(_28&&!dojo.hasClass(_28,"dijitCalendarDisabledDate")){
this.attr("value",_28.dijitDateValue);
this.onValueSelected(this.attr("value"));
}
},_onDayMouseOver:function(evt){
var _29=evt.target;
if(_29&&(_29.dijitDateValue||_29==this.previousYearLabelNode||_29==this.nextYearLabelNode)){
dojo.addClass(_29,"dijitCalendarHoveredDate");
this._currentNode=_29;
}
},_onDayMouseOut:function(evt){
if(!this._currentNode){
return;
}
for(var _2a=evt.relatedTarget;_2a;){
if(_2a==this._currentNode){
return;
}
try{
_2a=_2a.parentNode;
}
catch(x){
_2a=null;
}
}
dojo.removeClass(this._currentNode,"dijitCalendarHoveredDate");
this._currentNode=null;
},_onKeyPress:function(evt){
var dk=dojo.keys,_2b=-1,_2c,_2d=this.value;
switch(evt.keyCode){
case dk.RIGHT_ARROW:
_2b=1;
case dk.LEFT_ARROW:
_2c="day";
if(!this.isLeftToRight()){
_2b*=-1;
}
break;
case dk.DOWN_ARROW:
_2b=1;
case dk.UP_ARROW:
_2c="week";
break;
case dk.PAGE_DOWN:
_2b=1;
case dk.PAGE_UP:
_2c=evt.ctrlKey?"year":"month";
break;
case dk.END:
_2d=this.dateFuncObj.add(_2d,"month",1);
_2c="day";
case dk.HOME:
_2d=new Date(_2d).setDate(1);
break;
case dk.ENTER:
this.onValueSelected(this.attr("value"));
break;
case dk.ESCAPE:
default:
return;
}
dojo.stopEvent(evt);
if(_2c){
_2d=this.dateFuncObj.add(_2d,_2c,_2b);
}
this.attr("value",_2d);
},onValueSelected:function(_2e){
},onChange:function(_2f){
},_isSelectedDate:function(_30,_31){
return !this.dateFuncObj.compare(_30,this.value,"date");
},isDisabledDate:function(_32,_33){
},getClassForDate:function(_34,_35){
}});
}
