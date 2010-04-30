/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dijit.tree.TreeStoreModel"]){
dojo._hasResource["dijit.tree.TreeStoreModel"]=true;
dojo.provide("dijit.tree.TreeStoreModel");
dojo.declare("dijit.tree.TreeStoreModel",null,{store:null,childrenAttrs:["children"],newItemIdAttr:"id",labelAttr:"",root:null,query:null,deferItemLoadingUntilExpand:false,constructor:function(_1){
dojo.mixin(this,_1);
this.connects=[];
var _2=this.store;
if(!_2.getFeatures()["dojo.data.api.Identity"]){
throw new Error("dijit.Tree: store must support dojo.data.Identity");
}
if(_2.getFeatures()["dojo.data.api.Notification"]){
this.connects=this.connects.concat([dojo.connect(_2,"onNew",this,"onNewItem"),dojo.connect(_2,"onDelete",this,"onDeleteItem"),dojo.connect(_2,"onSet",this,"onSetItem")]);
}
},destroy:function(){
dojo.forEach(this.connects,dojo.disconnect);
},getRoot:function(_3,_4){
if(this.root){
_3(this.root);
}else{
this.store.fetch({query:this.query,onComplete:dojo.hitch(this,function(_5){
if(_5.length!=1){
throw new Error(this.declaredClass+": query "+dojo.toJson(this.query)+" returned "+_5.length+" items, but must return exactly one item");
}
this.root=_5[0];
_3(this.root);
}),onError:_4});
}
},mayHaveChildren:function(_6){
return dojo.some(this.childrenAttrs,function(_7){
return this.store.hasAttribute(_6,_7);
},this);
},getChildren:function(_8,_9,_a){
var _b=this.store;
if(!_b.isItemLoaded(_8)){
var _c=dojo.hitch(this,arguments.callee);
_b.loadItem({item:_8,onItem:function(_d){
_c(_d,_9,_a);
},onError:_a});
return;
}
var _e=[];
for(var i=0;i<this.childrenAttrs.length;i++){
var _f=_b.getValues(_8,this.childrenAttrs[i]);
_e=_e.concat(_f);
}
var _10=0;
if(!this.deferItemLoadingUntilExpand){
dojo.forEach(_e,function(_11){
if(!_b.isItemLoaded(_11)){
_10++;
}
});
}
if(_10==0){
_9(_e);
}else{
var _12=function _12(_13){
if(--_10==0){
_9(_e);
}
};
dojo.forEach(_e,function(_14){
if(!_b.isItemLoaded(_14)){
_b.loadItem({item:_14,onItem:_12,onError:_a});
}
});
}
},isItem:function(_15){
return this.store.isItem(_15);
},fetchItemByIdentity:function(_16){
this.store.fetchItemByIdentity(_16);
},getIdentity:function(_17){
return this.store.getIdentity(_17);
},getLabel:function(_18){
if(this.labelAttr){
return this.store.getValue(_18,this.labelAttr);
}else{
return this.store.getLabel(_18);
}
},newItem:function(_19,_1a,_1b){
var _1c={parent:_1a,attribute:this.childrenAttrs[0],insertIndex:_1b};
if(this.newItemIdAttr&&_19[this.newItemIdAttr]){
this.fetchItemByIdentity({identity:_19[this.newItemIdAttr],scope:this,onItem:function(_1d){
if(_1d){
this.pasteItem(_1d,null,_1a,true,_1b);
}else{
this.store.newItem(_19,_1c);
}
}});
}else{
this.store.newItem(_19,_1c);
}
},pasteItem:function(_1e,_1f,_20,_21,_22){
var _23=this.store,_24=this.childrenAttrs[0];
if(_1f){
dojo.forEach(this.childrenAttrs,function(_25){
if(_23.containsValue(_1f,_25,_1e)){
if(!_21){
var _26=dojo.filter(_23.getValues(_1f,_25),function(x){
return x!=_1e;
});
_23.setValues(_1f,_25,_26);
}
_24=_25;
}
});
}
if(_20){
if(typeof _22=="number"){
var _27=_23.getValues(_20,_24);
_27.splice(_22,0,_1e);
_23.setValues(_20,_24,_27);
}else{
_23.setValues(_20,_24,_23.getValues(_20,_24).concat(_1e));
}
}
},onChange:function(_28){
},onChildrenChange:function(_29,_2a){
},onDelete:function(_2b,_2c){
},onNewItem:function(_2d,_2e){
if(!_2e){
return;
}
this.getChildren(_2e.item,dojo.hitch(this,function(_2f){
this.onChildrenChange(_2e.item,_2f);
}));
},onDeleteItem:function(_30){
this.onDelete(_30);
},onSetItem:function(_31,_32,_33,_34){
if(dojo.indexOf(this.childrenAttrs,_32)!=-1){
this.getChildren(_31,dojo.hitch(this,function(_35){
this.onChildrenChange(_31,_35);
}));
}else{
this.onChange(_31);
}
}});
}
