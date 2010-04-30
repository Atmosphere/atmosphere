/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dojox.data.StoreExplorer"]){
dojo._hasResource["dojox.data.StoreExplorer"]=true;
dojo.provide("dojox.data.StoreExplorer");
dojo.require("dojox.grid.DataGrid");
dojo.require("dojox.data.ItemExplorer");
dojo.require("dijit.layout.BorderContainer");
dojo.require("dijit.layout.ContentPane");
dojo.declare("dojox.data.StoreExplorer",dijit.layout.BorderContainer,{constructor:function(_1){
dojo.mixin(this,_1);
},store:null,stringQueries:false,postCreate:function(){
var _2=this;
this.inherited(arguments);
var _3=new dijit.layout.ContentPane({region:"top"}).placeAt(this);
function _4(_5,_6){
var _7=new dijit.form.Button({label:_5});
_3.containerNode.appendChild(_7.domNode);
_7.onClick=_6;
return _7;
};
var _8=_3.containerNode.appendChild(document.createElement("span"));
_8.innerHTML="Enter query: &nbsp;";
_8.id="queryText";
var _9=_3.containerNode.appendChild(document.createElement("input"));
_9.type="text";
_9.id="queryTextBox";
_4("Query",function(){
var _a=_9.value;
_2.setQuery(_2.stringQueries?_a:dojo.fromJson(_a));
});
_3.containerNode.appendChild(document.createElement("span")).innerHTML="&nbsp;&nbsp;&nbsp;";
var _b=_4("Create New",dojo.hitch(this,"createNew"));
var _c=_4("Delete",function(){
var _d=_e.selection.getSelected();
for(var i=0;i<_d.length;i++){
_2.store.deleteItem(_d[i]);
}
});
this.setItemName=function(_f){
_b.attr("label","<img style='width:12px; height:12px' src='"+dojo.moduleUrl("dijit.themes.tundra.images","dndCopy.png")+"' /> Create New "+_f);
_c.attr("label","Delete "+_f);
};
_4("Save",function(){
_2.store.save({onError:function(_10){
alert(_10);
}});
_2.tree.refreshItem();
});
_4("Revert",function(){
_2.store.revert();
});
_4("Add Column",function(){
var _11=prompt("Enter column name:","property");
if(_11){
_2.gridLayout.push({field:_11,name:_11,formatter:dojo.hitch(_2,"_formatCell"),editable:true});
_2.grid.attr("structure",_2.gridLayout);
}
});
var _12=new dijit.layout.ContentPane({region:"center"}).placeAt(this);
var _e=this.grid=new dojox.grid.DataGrid({store:this.store});
_12.attr("content",_e);
_e.canEdit=function(_13,_14){
var _15=this._copyAttr(_14,_13.field);
return !(_15&&typeof _15=="object")||_15 instanceof Date;
};
var _16=new dijit.layout.ContentPane({region:"trailing",splitter:true,style:"width: 300px"}).placeAt(this);
var _17=this.tree=new dojox.data.ItemExplorer({store:this.store});
_16.attr("content",_17);
dojo.connect(_e,"onCellClick",function(){
var _18=_e.selection.getSelected()[0];
_17.setItem(_18);
});
this.gridOnFetchComplete=_e._onFetchComplete;
this.setStore(this.store);
},setQuery:function(_19){
this.grid.setQuery(_19);
},_formatCell:function(_1a){
if(this.store.isItem(_1a)){
return this.store.getLabel(_1a)||this.store.getIdentity(_1a);
}
return _1a;
},setStore:function(_1b){
this.store=_1b;
var _1c=this;
var _1d=this.grid;
_1d._pending_requests[0]=false;
function _1e(_1f){
return _1c._formatCell(_1f);
};
var _20=this.gridOnFetchComplete;
_1d._onFetchComplete=function(_21,req){
var _22=_1c.gridLayout=[];
var _23,key,_24,i,j,k,_25=_1b.getIdentityAttributes();
for(i=0;i<_25.length;i++){
key=_25[i];
_22.push({field:key,name:key,_score:100,formatter:_1e,editable:false});
}
for(i=0;_24=_21[i++];){
var _26=_1b.getAttributes(_24);
for(k=0;key=_26[k++];){
var _27=false;
for(j=0;_23=_22[j++];){
if(_23.field==key){
_23._score++;
_27=true;
break;
}
}
if(!_27){
_22.push({field:key,name:key,_score:1,formatter:_1e,styles:"white-space:nowrap; ",editable:true});
}
}
}
_22=_22.sort(function(a,b){
return a._score>b._score?-1:1;
});
for(j=0;_23=_22[j];j++){
if(_23._score<_21.length/40*j){
_22.splice(j,_22.length-j);
break;
}
}
for(j=0;_23=_22[j++];){
_23.width=Math.round(100/_22.length)+"%";
}
_1d._onFetchComplete=_20;
_1d.attr("structure",_22);
var _28=_20.apply(this,arguments);
};
_1d.setStore(_1b);
this.queryOptions={cache:true};
this.tree.setStore(_1b);
},createNew:function(){
var _29=prompt("Enter any properties (in JSON literal form) to put in the new item (passed to the newItem constructor):","{ }");
if(_29){
try{
this.store.newItem(dojo.fromJson(_29));
}
catch(e){
alert(e);
}
}
}});
}
