/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dojox.grid.enhanced._Builder"]){
dojo._hasResource["dojox.grid.enhanced._Builder"]=true;
dojo.provide("dojox.grid.enhanced._Builder");
dojo.require("dojox.grid._Builder");
dojo.declare("dojox.grid.enhanced._BuilderMixin",null,{generateCellMarkup:function(_1,_2,_3,_4){
var _5=this.inherited(arguments);
if(!_4){
_5[4]+="<div class=\"dojoxGridCellContent\">";
_5[6]="</div></td>";
}
return _5;
},domouseup:function(e){
if(e.cellNode){
this.grid.onMouseUp(e);
}
}});
dojo.declare("dojox.grid.enhanced._HeaderBuilder",[dojox.grid._HeaderBuilder,dojox.grid.enhanced._BuilderMixin],{getCellX:function(e){
if(this.grid.nestedSorting){
var _6=function(_7,_8){
for(var n=_7;n&&_8(n);n=n.parentNode){
}
return n;
};
var _9=function(_a){
var _b=_a.toUpperCase();
return function(_c){
return _c.tagName!=_b;
};
};
var no=_6(e.target,_9("th"));
var x=no?e.pageX-dojo.coords(no,true).x:-1;
if(dojo.isIE){
var _d=dojo.body().getBoundingClientRect();
var _e=(_d.right-_d.left)/document.body.clientWidth;
return parseInt(x/_e);
}
return x;
}
return this.inherited(arguments);
},decorateEvent:function(e){
var _f=this.inherited(arguments);
if(this.grid.nestedSorting){
var _10=this.grid._getSortEventInfo(e);
e.unarySortChoice=_10.unarySortChoice;
e.nestedSortChoice=_10.nestedSortChoice;
e.selectChoice=_10.selectChoice;
}
return _f;
},doclick:function(e){
if((this._skipBogusClicks&&!this.grid.nestedSorting)||(this.grid.nestedSorting&&this.grid.ignoreEvent(e))){
dojo.stopEvent(e);
return true;
}
},colResizeSetup:function(e,_11){
var _12=this.minColWidth;
if(e.sourceView.grid.nestedSorting&&!this.grid.pluginMgr.isFixedCell(e.cell)){
this.minColWidth=this.grid.getMinColWidth();
var _13=dojo.connect(this,"endResizeColumn",dojo.hitch(this,function(){
this.minColWidth=_12;
dojo.disconnect(_13);
}));
}
var _14=this.inherited(arguments);
if(!dojo._isBodyLtr()&&dojo.isIE&&_14.followers){
dojo.forEach(_14.followers,function(_15){
if(!_15.left){
_15.left=dojo.position(_15.node).x;
}
});
}
return _14;
}});
dojo.declare("dojox.grid.enhanced._ContentBuilder",[dojox.grid._ContentBuilder,dojox.grid.enhanced._BuilderMixin],{});
}
