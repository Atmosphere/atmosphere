/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dojox.form.DateTextBox"]){
dojo._hasResource["dojox.form.DateTextBox"]=true;
dojo.provide("dojox.form.DateTextBox");
dojo.experimental("dojox.form.DateTextBox");
dojo.require("dojox.widget.Calendar");
dojo.require("dojox.widget.CalendarViews");
dojo.require("dijit.form._DateTimeTextBox");
dojo.declare("dojox.form.DateTextBox",dijit.form._DateTimeTextBox,{popupClass:"dojox.widget.Calendar",_selector:"date",_open:function(){
this.inherited(arguments);
dojo.style(this._picker.domNode.parentNode,"position","absolute");
}});
dojo.declare("dojox.form.DayTextBox",dojox.form.DateTextBox,{popupClass:"dojox.widget.DailyCalendar",parse:function(_1){
return _1;
},format:function(_2){
return _2.getDate?_2.getDate():_2;
},validator:function(_3){
var _4=Number(_3);
var _5=/(^-?\d\d*$)/.test(String(_3));
return _3==""||_3==null||(_5&&_4>=1&&_4<=31);
},_open:function(){
this.inherited(arguments);
this._picker.onValueSelected=dojo.hitch(this,function(_6){
this.focus();
setTimeout(dojo.hitch(this,"_close"),1);
dijit.form.TextBox.prototype._setValueAttr.call(this,String(_6.getDate()),true,String(_6.getDate()));
});
}});
dojo.declare("dojox.form.MonthTextBox",dojox.form.DateTextBox,{popupClass:"dojox.widget.MonthlyCalendar",selector:"date",postMixInProperties:function(){
this.inherited(arguments);
this.constraints.datePattern="MM";
},format:function(_7){
return Number(_7)+1;
},parse:function(_8,_9){
return Number(_8)-1;
},serialize:function(_a,_b){
return String(_a);
},validator:function(_c){
var _d=Number(_c);
var _e=/(^-?\d\d*$)/.test(String(_c));
return _c==""||_c==null||(_e&&_d>=1&&_d<=12);
},_open:function(){
this.inherited(arguments);
this._picker.onValueSelected=dojo.hitch(this,function(_f){
this.focus();
setTimeout(dojo.hitch(this,"_close"),1);
dijit.form.TextBox.prototype._setValueAttr.call(this,_f,true,_f);
});
}});
dojo.declare("dojox.form.YearTextBox",dojox.form.DateTextBox,{popupClass:"dojox.widget.YearlyCalendar",format:function(_10){
if(typeof _10=="string"){
return _10;
}else{
if(_10.getFullYear){
return _10.getFullYear();
}
}
return _10;
},validator:function(_11){
return _11==""||_11==null||/(^-?\d\d*$)/.test(String(_11));
},_open:function(){
this.inherited(arguments);
this._picker.onValueSelected=dojo.hitch(this,function(_12){
this.focus();
setTimeout(dojo.hitch(this,"_close"),1);
dijit.form.TextBox.prototype._setValueAttr.call(this,_12,true,_12);
});
},parse:function(_13,_14){
return _13||(this._isEmpty(_13)?null:undefined);
},filter:function(val){
if(val&&val.getFullYear){
return val.getFullYear().toString();
}
return this.inherited(arguments);
}});
}
