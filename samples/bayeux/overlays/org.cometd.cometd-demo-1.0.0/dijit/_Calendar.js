/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dijit._Calendar"]){
dojo._hasResource["dijit._Calendar"]=true;
dojo.provide("dijit._Calendar");
dojo.require("dojo.cldr.supplemental");
dojo.require("dojo.date");
dojo.require("dojo.date.locale");
dojo.require("dijit._Widget");
dojo.require("dijit._Templated");
dojo.declare("dijit._Calendar",[dijit._Widget,dijit._Templated],{templateString:"<table cellspacing=\"0\" cellpadding=\"0\" class=\"dijitCalendarContainer\">\n\t<thead>\n\t\t<tr class=\"dijitReset dijitCalendarMonthContainer\" valign=\"top\">\n\t\t\t<th class='dijitReset' dojoAttachPoint=\"decrementMonth\">\n\t\t\t\t<img src=\"${_blankGif}\" alt=\"\" class=\"dijitCalendarIncrementControl dijitCalendarDecrease\" waiRole=\"presentation\">\n\t\t\t\t<span dojoAttachPoint=\"decreaseArrowNode\" class=\"dijitA11ySideArrow\">-</span>\n\t\t\t</th>\n\t\t\t<th class='dijitReset' colspan=\"5\">\n\t\t\t\t<div dojoAttachPoint=\"monthLabelSpacer\" class=\"dijitCalendarMonthLabelSpacer\"></div>\n\t\t\t\t<div dojoAttachPoint=\"monthLabelNode\" class=\"dijitCalendarMonthLabel\"></div>\n\t\t\t</th>\n\t\t\t<th class='dijitReset' dojoAttachPoint=\"incrementMonth\">\n\t\t\t\t<img src=\"${_blankGif}\" alt=\"\" class=\"dijitCalendarIncrementControl dijitCalendarIncrease\" waiRole=\"presentation\">\n\t\t\t\t<span dojoAttachPoint=\"increaseArrowNode\" class=\"dijitA11ySideArrow\">+</span>\n\t\t\t</th>\n\t\t</tr>\n\t\t<tr>\n\t\t\t<th class=\"dijitReset dijitCalendarDayLabelTemplate\"><span class=\"dijitCalendarDayLabel\"></span></th>\n\t\t</tr>\n\t</thead>\n\t<tbody dojoAttachEvent=\"onclick: _onDayClick, onmouseover: _onDayMouseOver, onmouseout: _onDayMouseOut\" class=\"dijitReset dijitCalendarBodyContainer\">\n\t\t<tr class=\"dijitReset dijitCalendarWeekTemplate\">\n\t\t\t<td class=\"dijitReset dijitCalendarDateTemplate\"><span class=\"dijitCalendarDateLabel\"></span></td>\n\t\t</tr>\n\t</tbody>\n\t<tfoot class=\"dijitReset dijitCalendarYearContainer\">\n\t\t<tr>\n\t\t\t<td class='dijitReset' valign=\"top\" colspan=\"7\">\n\t\t\t\t<h3 class=\"dijitCalendarYearLabel\">\n\t\t\t\t\t<span dojoAttachPoint=\"previousYearLabelNode\" class=\"dijitInline dijitCalendarPreviousYear\"></span>\n\t\t\t\t\t<span dojoAttachPoint=\"currentYearLabelNode\" class=\"dijitInline dijitCalendarSelectedYear\"></span>\n\t\t\t\t\t<span dojoAttachPoint=\"nextYearLabelNode\" class=\"dijitInline dijitCalendarNextYear\"></span>\n\t\t\t\t</h3>\n\t\t\t</td>\n\t\t</tr>\n\t</tfoot>\n</table>\t\n",value:new Date(),dayWidth:"narrow",setValue:function(_1){
dojo.deprecated("dijit.Calendar:setValue() is deprecated.  Use attr('value', ...) instead.","","2.0");
this.attr("value",_1);
},_getValueAttr:function(_2){
var _2=new Date(this.value);
_2.setHours(0,0,0,0);
if(_2.getDate()<this.value.getDate()){
_2=dojo.date.add(_2,"hour",1);
}
return _2;
},_setValueAttr:function(_3){
if(!this.value||dojo.date.compare(_3,this.value)){
_3=new Date(_3);
_3.setHours(1);
this.displayMonth=new Date(_3);
if(!this.isDisabledDate(_3,this.lang)){
this.value=_3;
this.onChange(this.attr("value"));
}
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
var _7=_6.getDay();
var _8=dojo.date.getDaysInMonth(_6);
var _9=dojo.date.getDaysInMonth(dojo.date.add(_6,"month",-1));
var _a=new Date();
var _b=this.value;
var _c=dojo.cldr.supplemental.getFirstDayOfWeek(this.lang);
if(_c>_7){
_c-=7;
}
dojo.query(".dijitCalendarDateTemplate",this.domNode).forEach(function(_d,i){
i+=_c;
var _f=new Date(_6);
var _10,_11="dijitCalendar",adj=0;
if(i<_7){
_10=_9-_7+i+1;
adj=-1;
_11+="Previous";
}else{
if(i>=(_7+_8)){
_10=i-_7-_8+1;
adj=1;
_11+="Next";
}else{
_10=i-_7+1;
_11+="Current";
}
}
if(adj){
_f=dojo.date.add(_f,"month",adj);
}
_f.setDate(_10);
if(!dojo.date.compare(_f,_a,"date")){
_11="dijitCalendarCurrentDate "+_11;
}
if(!dojo.date.compare(_f,_b,"date")){
_11="dijitCalendarSelectedDate "+_11;
}
if(this.isDisabledDate(_f,this.lang)){
_11="dijitCalendarDisabledDate "+_11;
}
var _13=this.getClassForDate(_f,this.lang);
if(_13){
_11=_13+" "+_11;
}
_d.className=_11+"Month dijitCalendarDateTemplate";
_d.dijitDateValue=_f.valueOf();
var _14=dojo.query(".dijitCalendarDateLabel",_d)[0];
this._setText(_14,_f.getDate());
},this);
var _15=dojo.date.locale.getNames("months","wide","standAlone",this.lang);
this._setText(this.monthLabelNode,_15[_6.getMonth()]);
var y=_6.getFullYear()-1;
var d=new Date();
dojo.forEach(["previous","current","next"],function(_18){
d.setFullYear(y++);
this._setText(this[_18+"YearLabelNode"],dojo.date.locale.format(d,{selector:"year",locale:this.lang}));
},this);
var _19=this;
var _1a=function(_1b,_1c,adj){
_19._connects.push(dijit.typematic.addMouseListener(_19[_1b],_19,function(_1e){
if(_1e>=0){
_19._adjustDisplay(_1c,adj);
}
},0.8,500));
};
_1a("incrementMonth","month",1);
_1a("decrementMonth","month",-1);
_1a("nextYearLabelNode","year",1);
_1a("previousYearLabelNode","year",-1);
},goToToday:function(){
this.attr("value",new Date());
},postCreate:function(){
this.inherited(arguments);
dojo.setSelectable(this.domNode,false);
var _1f=dojo.hitch(this,function(_20,n){
var _22=dojo.query(_20,this.domNode)[0];
for(var i=0;i<n;i++){
_22.parentNode.appendChild(_22.cloneNode(true));
}
});
_1f(".dijitCalendarDayLabelTemplate",6);
_1f(".dijitCalendarDateTemplate",6);
_1f(".dijitCalendarWeekTemplate",5);
var _24=dojo.date.locale.getNames("days",this.dayWidth,"standAlone",this.lang);
var _25=dojo.cldr.supplemental.getFirstDayOfWeek(this.lang);
dojo.query(".dijitCalendarDayLabel",this.domNode).forEach(function(_26,i){
this._setText(_26,_24[(i+_25)%7]);
},this);
var _28=dojo.date.locale.getNames("months","wide","standAlone",this.lang);
dojo.forEach(_28,function(_29){
var _2a=dojo.create("div",null,this.monthLabelSpacer);
this._setText(_2a,_29);
},this);
this.value=null;
this.attr("value",new Date());
},_adjustDisplay:function(_2b,_2c){
this.displayMonth=dojo.date.add(this.displayMonth,_2b,_2c);
this._populateGrid();
},_onDayClick:function(evt){
dojo.stopEvent(evt);
for(var _2e=evt.target;_2e&&!_2e.dijitDateValue;_2e=_2e.parentNode){
}
if(_2e&&!dojo.hasClass(_2e,"dijitCalendarDisabledDate")){
this.attr("value",_2e.dijitDateValue);
this.onValueSelected(this.attr("value"));
}
},_onDayMouseOver:function(evt){
var _30=evt.target;
if(_30&&(_30.dijitDateValue||_30==this.previousYearLabelNode||_30==this.nextYearLabelNode)){
dojo.addClass(_30,"dijitCalendarHoveredDate");
this._currentNode=_30;
}
},_onDayMouseOut:function(evt){
if(!this._currentNode){
return;
}
for(var _32=evt.relatedTarget;_32;){
if(_32==this._currentNode){
return;
}
try{
_32=_32.parentNode;
}
catch(x){
_32=null;
}
}
dojo.removeClass(this._currentNode,"dijitCalendarHoveredDate");
this._currentNode=null;
},onValueSelected:function(_33){
},onChange:function(_34){
},isDisabledDate:function(_35,_36){
},getClassForDate:function(_37,_38){
}});
}
