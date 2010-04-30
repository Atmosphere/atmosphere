/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dojox.widget.Calendar"]){
dojo._hasResource["dojox.widget.Calendar"]=true;
dojo.provide("dojox.widget.Calendar");
dojo.experimental("dojox.widget.Calendar");
dojo.require("dijit.Calendar");
dojo.require("dijit._Container");
dojo.declare("dojox.widget._CalendarBase",[dijit._Widget,dijit._Templated,dijit._Container],{templateString:dojo.cache("dojox.widget","Calendar/Calendar.html","<div class=\"dojoxCalendar\">\n    <div tabindex=\"0\" class=\"dojoxCalendarContainer\" style=\"visibility: visible;\" dojoAttachPoint=\"container\">\n\t\t<div style=\"display:none\">\n\t\t\t<div dojoAttachPoint=\"previousYearLabelNode\"></div>\n\t\t\t<div dojoAttachPoint=\"nextYearLabelNode\"></div>\n\t\t\t<div dojoAttachPoint=\"monthLabelSpacer\"></div>\n\t\t</div>\n        <div class=\"dojoxCalendarHeader\">\n            <div>\n                <div class=\"dojoxCalendarDecrease\" dojoAttachPoint=\"decrementMonth\"></div>\n            </div>\n            <div class=\"\">\n                <div class=\"dojoxCalendarIncrease\" dojoAttachPoint=\"incrementMonth\"></div>\n            </div>\n            <div class=\"dojoxCalendarTitle\" dojoAttachPoint=\"header\" dojoAttachEvent=\"onclick: onHeaderClick\">\n            </div>\n        </div>\n        <div class=\"dojoxCalendarBody\" dojoAttachPoint=\"containerNode\"></div>\n        <div class=\"\">\n            <div class=\"dojoxCalendarFooter\" dojoAttachPoint=\"footer\">                        \n            </div>\n        </div>\n    </div>\n</div>\n"),_views:null,useFx:true,widgetsInTemplate:true,value:new Date(),constraints:null,footerFormat:"medium",constructor:function(){
this._views=[];
},postMixInProperties:function(){
var c=this.constraints;
if(c){
var _1=dojo.date.stamp.fromISOString;
if(typeof c.min=="string"){
c.min=_1(c.min);
}
if(typeof c.max=="string"){
c.max=_1(c.max);
}
}
},postCreate:function(){
this.displayMonth=new Date(this.attr("value"));
var _2={parent:this,_getValueAttr:dojo.hitch(this,function(){
return new Date(this._internalValue||this.value);
}),_getDisplayMonthAttr:dojo.hitch(this,function(){
return new Date(this.displayMonth);
}),_getConstraintsAttr:dojo.hitch(this,function(){
return this.constraints;
}),getLang:dojo.hitch(this,function(){
return this.lang;
}),isDisabledDate:dojo.hitch(this,this.isDisabledDate),getClassForDate:dojo.hitch(this,this.getClassForDate),addFx:this.useFx?dojo.hitch(this,this.addFx):function(){
}};
dojo.forEach(this._views,function(_3){
var _4=new _3(_2,dojo.create("div"));
this.addChild(_4);
var _5=_4.getHeader();
if(_5){
this.header.appendChild(_5);
dojo.style(_5,"display","none");
}
dojo.style(_4.domNode,"visibility","hidden");
dojo.connect(_4,"onValueSelected",this,"_onDateSelected");
_4.attr("value",this.attr("value"));
},this);
if(this._views.length<2){
dojo.style(this.header,"cursor","auto");
}
this.inherited(arguments);
this._children=this.getChildren();
this._currentChild=0;
var _6=new Date();
this.footer.innerHTML="Today: "+dojo.date.locale.format(_6,{formatLength:this.footerFormat,selector:"date",locale:this.lang});
dojo.connect(this.footer,"onclick",this,"goToToday");
var _7=this._children[0];
dojo.style(_7.domNode,"top","0px");
dojo.style(_7.domNode,"visibility","visible");
var _8=_7.getHeader();
if(_8){
dojo.style(_7.getHeader(),"display","");
}
dojo[_7.useHeader?"removeClass":"addClass"](this.container,"no-header");
_7.onDisplay();
var _9=this;
var _a=function(_b,_c,_d){
dijit.typematic.addMouseListener(_9[_b],_9,function(_e){
if(_e>=0){
_9._adjustDisplay(_c,_d);
}
},0.8,500);
};
_a("incrementMonth","month",1);
_a("decrementMonth","month",-1);
this._updateTitleStyle();
},addFx:function(_f,_10){
},_setValueAttr:function(_11){
if(!_11["getFullYear"]){
_11=dojo.date.stamp.fromISOString(_11+"");
}
if(!this.value||dojo.date.compare(_11,this.value)){
_11=new Date(_11);
this.displayMonth=new Date(_11);
this._internalValue=_11;
if(!this.isDisabledDate(_11,this.lang)&&this._currentChild==0){
this.value=_11;
this.onChange(_11);
}
this._children[this._currentChild].attr("value",this.value);
return true;
}
return false;
},isDisabledDate:function(_12,_13){
var c=this.constraints;
var _14=dojo.date.compare;
return c&&(c.min&&(_14(c.min,_12,"date")>0)||(c.max&&_14(c.max,_12,"date")<0));
},onValueSelected:function(_15){
},_onDateSelected:function(_16,_17,_18){
this.displayMonth=_16;
this.attr("value",_16);
if(!this._transitionVert(-1)){
if(!_17&&_17!==0){
_17=this.attr("value");
}
this.onValueSelected(_17);
}
},onChange:function(_19){
},onHeaderClick:function(e){
this._transitionVert(1);
},goToToday:function(){
this.attr("value",new Date());
this.onValueSelected(this.attr("value"));
},_transitionVert:function(_1a){
var _1b=this._children[this._currentChild];
var _1c=this._children[this._currentChild+_1a];
if(!_1c){
return false;
}
dojo.style(_1c.domNode,"visibility","visible");
var _1d=dojo.style(this.containerNode,"height");
_1c.attr("value",this.displayMonth);
if(_1b.header){
dojo.style(_1b.header,"display","none");
}
if(_1c.header){
dojo.style(_1c.header,"display","");
}
dojo.style(_1c.domNode,"top",(_1d*-1)+"px");
dojo.style(_1c.domNode,"visibility","visible");
this._currentChild+=_1a;
var _1e=_1d*_1a;
var _1f=0;
dojo.style(_1c.domNode,"top",(_1e*-1)+"px");
var _20=dojo.animateProperty({node:_1b.domNode,properties:{top:_1e},onEnd:function(){
dojo.style(_1b.domNode,"visibility","hidden");
}});
var _21=dojo.animateProperty({node:_1c.domNode,properties:{top:_1f},onEnd:function(){
_1c.onDisplay();
}});
dojo[_1c.useHeader?"removeClass":"addClass"](this.container,"no-header");
_20.play();
_21.play();
_1b.onBeforeUnDisplay();
_1c.onBeforeDisplay();
this._updateTitleStyle();
return true;
},_updateTitleStyle:function(){
dojo[this._currentChild<this._children.length-1?"addClass":"removeClass"](this.header,"navToPanel");
},_slideTable:function(_22,_23,_24){
var _25=_22.domNode;
var _26=_25.cloneNode(true);
var _27=dojo.style(_25,"width");
_25.parentNode.appendChild(_26);
dojo.style(_25,"left",(_27*_23)+"px");
_24();
var _28=dojo.animateProperty({node:_26,properties:{left:_27*_23*-1},duration:500,onEnd:function(){
_26.parentNode.removeChild(_26);
}});
var _29=dojo.animateProperty({node:_25,properties:{left:0},duration:500});
_28.play();
_29.play();
},_addView:function(_2a){
this._views.push(_2a);
},getClassForDate:function(_2b,_2c){
},_adjustDisplay:function(_2d,_2e,_2f){
var _30=this._children[this._currentChild];
var _31=this.displayMonth=_30.adjustDate(this.displayMonth,_2e);
this._slideTable(_30,_2e,function(){
_30.attr("value",_31);
});
}});
dojo.declare("dojox.widget._CalendarView",dijit._Widget,{headerClass:"",useHeader:true,cloneClass:function(_32,n,_33){
var _34=dojo.query(_32,this.domNode)[0];
var i;
if(!_33){
for(i=0;i<n;i++){
_34.parentNode.appendChild(_34.cloneNode(true));
}
}else{
var _35=dojo.query(_32,this.domNode)[0];
for(i=0;i<n;i++){
_34.parentNode.insertBefore(_34.cloneNode(true),_35);
}
}
},_setText:function(_36,_37){
if(_36.innerHTML!=_37){
dojo.empty(_36);
_36.appendChild(dojo.doc.createTextNode(_37));
}
},getHeader:function(){
return this.header||(this.header=this.header=dojo.create("span",{"class":this.headerClass}));
},onValueSelected:function(_38){
},adjustDate:function(_39,_3a){
return dojo.date.add(_39,this.datePart,_3a);
},onDisplay:function(){
},onBeforeDisplay:function(){
},onBeforeUnDisplay:function(){
}});
dojo.declare("dojox.widget._CalendarDay",null,{parent:null,constructor:function(){
this._addView(dojox.widget._CalendarDayView);
}});
dojo.declare("dojox.widget._CalendarDayView",[dojox.widget._CalendarView,dijit._Templated],{templateString:dojo.cache("dojox.widget","Calendar/CalendarDay.html","<div class=\"dijitCalendarDayLabels\" style=\"left: 0px;\" dojoAttachPoint=\"dayContainer\">\n\t<div dojoAttachPoint=\"header\">\n\t\t<div dojoAttachPoint=\"monthAndYearHeader\">\n\t\t\t<span dojoAttachPoint=\"monthLabelNode\" class=\"dojoxCalendarMonthLabelNode\"></span>\n\t\t\t<span dojoAttachPoint=\"headerComma\" class=\"dojoxCalendarComma\">,</span>\n\t\t\t<span dojoAttachPoint=\"yearLabelNode\" class=\"dojoxCalendarDayYearLabel\"></span>\n\t\t</div>\n\t</div>\n\t<table cellspacing=\"0\" cellpadding=\"0\" border=\"0\" style=\"margin: auto;\">\n\t\t<thead>\n\t\t\t<tr>\n\t\t\t\t<td class=\"dijitCalendarDayLabelTemplate\"><div class=\"dijitCalendarDayLabel\"></div></td>\n\t\t\t</tr>\n\t\t</thead>\n\t\t<tbody dojoAttachEvent=\"onclick: _onDayClick\">\n\t\t\t<tr class=\"dijitCalendarWeekTemplate\">\n\t\t\t\t<td class=\"dojoxCalendarNextMonth dijitCalendarDateTemplate\">\n\t\t\t\t\t<div class=\"dijitCalendarDateLabel\"></div>\n\t\t\t\t</td>\n\t\t\t</tr>\n\t\t</tbody>\n\t</table>\n</div>\n"),datePart:"month",dayWidth:"narrow",postCreate:function(){
this.cloneClass(".dijitCalendarDayLabelTemplate",6);
this.cloneClass(".dijitCalendarDateTemplate",6);
this.cloneClass(".dijitCalendarWeekTemplate",5);
var _3b=dojo.date.locale.getNames("days",this.dayWidth,"standAlone",this.getLang());
var _3c=dojo.cldr.supplemental.getFirstDayOfWeek(this.getLang());
dojo.query(".dijitCalendarDayLabel",this.domNode).forEach(function(_3d,i){
this._setText(_3d,_3b[(i+_3c)%7]);
},this);
},onDisplay:function(){
if(!this._addedFx){
this._addedFx=true;
this.addFx(".dijitCalendarDateTemplate div",this.domNode);
}
},_onDayClick:function(e){
if(typeof (e.target._date)=="undefined"){
return;
}
var _3e=new Date(this.attr("displayMonth"));
var p=e.target.parentNode;
var c="dijitCalendar";
var d=dojo.hasClass(p,c+"PreviousMonth")?-1:(dojo.hasClass(p,c+"NextMonth")?1:0);
if(d){
_3e=dojo.date.add(_3e,"month",d);
}
_3e.setDate(e.target._date);
if(this.isDisabledDate(_3e)){
dojo.stopEvent(e);
return;
}
this.parent._onDateSelected(_3e);
},_setValueAttr:function(_3f){
this._populateDays();
},_populateDays:function(){
var _40=new Date(this.attr("displayMonth"));
_40.setDate(1);
var _41=_40.getDay();
var _42=dojo.date.getDaysInMonth(_40);
var _43=dojo.date.getDaysInMonth(dojo.date.add(_40,"month",-1));
var _44=new Date();
var _45=this.attr("value");
var _46=dojo.cldr.supplemental.getFirstDayOfWeek(this.getLang());
if(_46>_41){
_46-=7;
}
var _47=dojo.date.compare;
var _48=".dijitCalendarDateTemplate";
var _49="dijitCalendarSelectedDate";
var _4a=this._lastDate;
var _4b=_4a==null||_4a.getMonth()!=_40.getMonth()||_4a.getFullYear()!=_40.getFullYear();
this._lastDate=_40;
if(!_4b){
dojo.query(_48,this.domNode).removeClass(_49).filter(function(_4c){
return _4c.className.indexOf("dijitCalendarCurrent")>-1&&_4c._date==_45.getDate();
}).addClass(_49);
return;
}
dojo.query(_48,this.domNode).forEach(function(_4d,i){
i+=_46;
var _4e=new Date(_40);
var _4f,_50="dijitCalendar",adj=0;
if(i<_41){
_4f=_43-_41+i+1;
adj=-1;
_50+="Previous";
}else{
if(i>=(_41+_42)){
_4f=i-_41-_42+1;
adj=1;
_50+="Next";
}else{
_4f=i-_41+1;
_50+="Current";
}
}
if(adj){
_4e=dojo.date.add(_4e,"month",adj);
}
_4e.setDate(_4f);
if(!_47(_4e,_44,"date")){
_50="dijitCalendarCurrentDate "+_50;
}
if(!_47(_4e,_45,"date")&&!_47(_4e,_45,"month")&&!_47(_4e,_45,"year")){
_50=_49+" "+_50;
}
if(this.isDisabledDate(_4e,this.getLang())){
_50=" dijitCalendarDisabledDate "+_50;
}
var _51=this.getClassForDate(_4e,this.getLang());
if(_51){
_50+=_51+" "+_50;
}
_4d.className=_50+"Month dijitCalendarDateTemplate";
_4d.dijitDateValue=_4e.valueOf();
var _52=dojo.query(".dijitCalendarDateLabel",_4d)[0];
this._setText(_52,_4e.getDate());
_52._date=_52.parentNode._date=_4e.getDate();
},this);
var _53=dojo.date.locale.getNames("months","wide","standAlone",this.getLang());
this._setText(this.monthLabelNode,_53[_40.getMonth()]);
this._setText(this.yearLabelNode,_40.getFullYear());
}});
dojo.declare("dojox.widget._CalendarMonthYear",null,{constructor:function(){
this._addView(dojox.widget._CalendarMonthYearView);
}});
dojo.declare("dojox.widget._CalendarMonthYearView",[dojox.widget._CalendarView,dijit._Templated],{templateString:dojo.cache("dojox.widget","Calendar/CalendarMonthYear.html","<div class=\"dojoxCal-MY-labels\" style=\"left: 0px;\"\t\n\tdojoAttachPoint=\"myContainer\" dojoAttachEvent=\"onclick: onClick\">\n\t\t<table cellspacing=\"0\" cellpadding=\"0\" border=\"0\" style=\"margin: auto;\">\n\t\t\t\t<tbody>\n\t\t\t\t\t\t<tr class=\"dojoxCal-MY-G-Template\">\n\t\t\t\t\t\t\t\t<td class=\"dojoxCal-MY-M-Template\">\n\t\t\t\t\t\t\t\t\t\t<div class=\"dojoxCalendarMonthLabel\"></div>\n\t\t\t\t\t\t\t\t</td>\n\t\t\t\t\t\t\t\t<td class=\"dojoxCal-MY-M-Template\">\n\t\t\t\t\t\t\t\t\t\t<div class=\"dojoxCalendarMonthLabel\"></div>\n\t\t\t\t\t\t\t\t</td>\n\t\t\t\t\t\t\t\t<td class=\"dojoxCal-MY-Y-Template\">\n\t\t\t\t\t\t\t\t\t\t<div class=\"dojoxCalendarYearLabel\"></div>\n\t\t\t\t\t\t\t\t</td>\n\t\t\t\t\t\t\t\t<td class=\"dojoxCal-MY-Y-Template\">\n\t\t\t\t\t\t\t\t\t\t<div class=\"dojoxCalendarYearLabel\"></div>\n\t\t\t\t\t\t\t\t</td>\n\t\t\t\t\t\t </tr>\n\t\t\t\t\t\t <tr class=\"dojoxCal-MY-btns\">\n\t\t\t\t\t\t \t <td class=\"dojoxCal-MY-btns\" colspan=\"4\">\n\t\t\t\t\t\t \t\t <span class=\"dijitReset dijitInline dijitButtonNode ok-btn\" dojoAttachEvent=\"onclick: onOk\" dojoAttachPoint=\"okBtn\">\n\t\t\t\t\t\t \t \t \t <button\tclass=\"dijitReset dijitStretch dijitButtonContents\">OK</button>\n\t\t\t\t\t\t\t\t </span>\n\t\t\t\t\t\t\t\t <span class=\"dijitReset dijitInline dijitButtonNode cancel-btn\" dojoAttachEvent=\"onclick: onCancel\" dojoAttachPoint=\"cancelBtn\">\n\t\t\t\t\t\t \t \t\t <button\tclass=\"dijitReset dijitStretch dijitButtonContents\">Cancel</button>\n\t\t\t\t\t\t\t\t </span>\n\t\t\t\t\t\t \t </td>\n\t\t\t\t\t\t </tr>\n\t\t\t\t</tbody>\n\t\t</table>\n</div>\n"),datePart:"year",displayedYears:10,useHeader:false,postCreate:function(){
this.cloneClass(".dojoxCal-MY-G-Template",5,".dojoxCal-MY-btns");
this.monthContainer=this.yearContainer=this.myContainer;
var _54="dojoxCalendarYearLabel";
var _55="dojoxCalendarDecrease";
var _56="dojoxCalendarIncrease";
dojo.query("."+_54,this.myContainer).forEach(function(_57,idx){
var _58=_56;
switch(idx){
case 0:
_58=_55;
case 1:
dojo.removeClass(_57,_54);
dojo.addClass(_57,_58);
break;
}
});
this._decBtn=dojo.query("."+_55,this.myContainer)[0];
this._incBtn=dojo.query("."+_56,this.myContainer)[0];
dojo.query(".dojoxCal-MY-M-Template",this.domNode).filter(function(_59){
return _59.cellIndex==1;
}).addClass("dojoxCal-MY-M-last");
dojo.connect(this,"onBeforeDisplay",dojo.hitch(this,function(){
this._cachedDate=new Date(this.attr("value").getTime());
this._populateYears(this._cachedDate.getFullYear());
this._populateMonths();
this._updateSelectedMonth();
this._updateSelectedYear();
}));
dojo.connect(this,"_populateYears",dojo.hitch(this,function(){
this._updateSelectedYear();
}));
dojo.connect(this,"_populateMonths",dojo.hitch(this,function(){
this._updateSelectedMonth();
}));
this._cachedDate=this.attr("value");
this._populateYears();
this._populateMonths();
this.addFx(".dojoxCalendarMonthLabel,.dojoxCalendarYearLabel ",this.myContainer);
},_setValueAttr:function(_5a){
this._populateYears(_5a.getFullYear());
},getHeader:function(){
return null;
},_getMonthNames:function(_5b){
this._monthNames=this._monthNames||dojo.date.locale.getNames("months",_5b,"standAlone",this.getLang());
return this._monthNames;
},_populateMonths:function(){
var _5c=this._getMonthNames("abbr");
dojo.query(".dojoxCalendarMonthLabel",this.monthContainer).forEach(dojo.hitch(this,function(_5d,cnt){
this._setText(_5d,_5c[cnt]);
}));
var _5e=this.attr("constraints");
if(_5e){
var _5f=new Date();
_5f.setFullYear(this._year);
var min=-1,max=12;
if(_5e.min){
var _60=_5e.min.getFullYear();
if(_60>this._year){
min=12;
}else{
if(_60==this._year){
min=_5e.min.getMonth();
}
}
}
if(_5e.max){
var _61=_5e.max.getFullYear();
if(_61<this._year){
max=-1;
}else{
if(_61==this._year){
max=_5e.max.getMonth();
}
}
}
dojo.query(".dojoxCalendarMonthLabel",this.monthContainer).forEach(dojo.hitch(this,function(_62,cnt){
dojo[(cnt<min||cnt>max)?"addClass":"removeClass"](_62,"dijitCalendarDisabledDate");
}));
}
var h=this.getHeader();
if(h){
this._setText(this.getHeader(),this.attr("value").getFullYear());
}
},_populateYears:function(_63){
var _64=this.attr("constraints");
var _65=_63||this.attr("value").getFullYear();
var _66=_65-Math.floor(this.displayedYears/2);
var min=_64&&_64.min?_64.min.getFullYear():_66-10000;
_66=Math.max(min,_66);
this._displayedYear=_65;
var _67=dojo.query(".dojoxCalendarYearLabel",this.yearContainer);
var max=_64&&_64.max?_64.max.getFullYear()-_66:_67.length;
var _68="dijitCalendarDisabledDate";
_67.forEach(dojo.hitch(this,function(_69,cnt){
if(cnt<=max){
this._setText(_69,_66+cnt);
dojo.removeClass(_69,_68);
}else{
dojo.addClass(_69,_68);
}
}));
if(this._incBtn){
dojo[max<_67.length?"addClass":"removeClass"](this._incBtn,_68);
}
if(this._decBtn){
dojo[min>=_66?"addClass":"removeClass"](this._decBtn,_68);
}
var h=this.getHeader();
if(h){
this._setText(this.getHeader(),_66+" - "+(_66+11));
}
},_updateSelectedYear:function(){
this._year=String((this._cachedDate||this.attr("value")).getFullYear());
this._updateSelectedNode(".dojoxCalendarYearLabel",dojo.hitch(this,function(_6a,idx){
return this._year!==null&&_6a.innerHTML==this._year;
}));
},_updateSelectedMonth:function(){
var _6b=(this._cachedDate||this.attr("value")).getMonth();
this._month=_6b;
this._updateSelectedNode(".dojoxCalendarMonthLabel",function(_6c,idx){
return idx==_6b;
});
},_updateSelectedNode:function(_6d,_6e){
var sel="dijitCalendarSelectedDate";
dojo.query(_6d,this.domNode).forEach(function(_6f,idx,_70){
dojo[_6e(_6f,idx,_70)?"addClass":"removeClass"](_6f.parentNode,sel);
});
var _71=dojo.query(".dojoxCal-MY-M-Template div",this.myContainer).filter(function(_72){
return dojo.hasClass(_72.parentNode,sel);
})[0];
if(!_71){
return;
}
var _73=dojo.hasClass(_71,"dijitCalendarDisabledDate");
dojo[_73?"addClass":"removeClass"](this.okBtn,"dijitDisabled");
},onClick:function(evt){
var _74;
var _75=this;
var sel="dijitCalendarSelectedDate";
function hc(c){
return dojo.hasClass(evt.target,c);
};
if(hc("dijitCalendarDisabledDate")){
dojo.stopEvent(evt);
return false;
}
if(hc("dojoxCalendarMonthLabel")){
_74="dojoxCal-MY-M-Template";
this._month=evt.target.parentNode.cellIndex+(evt.target.parentNode.parentNode.rowIndex*2);
this._cachedDate.setMonth(this._month);
this._updateSelectedMonth();
}else{
if(hc("dojoxCalendarYearLabel")){
_74="dojoxCal-MY-Y-Template";
this._year=Number(evt.target.innerHTML);
this._cachedDate.setYear(this._year);
this._populateMonths();
this._updateSelectedYear();
}else{
if(hc("dojoxCalendarDecrease")){
this._populateYears(this._displayedYear-10);
return true;
}else{
if(hc("dojoxCalendarIncrease")){
this._populateYears(this._displayedYear+10);
return true;
}else{
return true;
}
}
}
}
dojo.stopEvent(evt);
return false;
},onOk:function(evt){
dojo.stopEvent(evt);
if(dojo.hasClass(this.okBtn,"dijitDisabled")){
return false;
}
this.onValueSelected(this._cachedDate);
return false;
},onCancel:function(evt){
dojo.stopEvent(evt);
this.onValueSelected(this.attr("value"));
return false;
}});
dojo.declare("dojox.widget.Calendar2Pane",[dojox.widget._CalendarBase,dojox.widget._CalendarDay,dojox.widget._CalendarMonthYear],{});
dojo.declare("dojox.widget.Calendar",[dojox.widget._CalendarBase,dojox.widget._CalendarDay,dojox.widget._CalendarMonthYear],{});
dojo.declare("dojox.widget.DailyCalendar",[dojox.widget._CalendarBase,dojox.widget._CalendarDay],{});
dojo.declare("dojox.widget.MonthAndYearlyCalendar",[dojox.widget._CalendarBase,dojox.widget._CalendarMonthYear],{});
}
