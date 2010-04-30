/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dijit.form.CurrencyTextBox"]){
dojo._hasResource["dijit.form.CurrencyTextBox"]=true;
dojo.provide("dijit.form.CurrencyTextBox");
dojo.require("dojo.currency");
dojo.require("dijit.form.NumberTextBox");
dojo.declare("dijit.form.CurrencyTextBox",dijit.form.NumberTextBox,{currency:"",regExpGen:function(_1){
return "("+(this._focused?this.inherited(arguments,[dojo.mixin({},_1,this.editOptions)])+"|":"")+dojo.currency.regexp(_1)+")";
},_formatter:dojo.currency.format,parse:function(_2,_3){
var v=dojo.currency.parse(_2,_3);
if(isNaN(v)&&/\d+/.test(_2)){
return this.inherited(arguments,[_2,dojo.mixin({},_3,this.editOptions)]);
}
return v;
},postMixInProperties:function(){
this.constraints=dojo.currency._mixInDefaults(dojo.mixin(this.constraints,{currency:this.currency,exponent:false}));
this.inherited(arguments);
}});
}
