/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dojox.charting.DataChart"]){
dojo._hasResource["dojox.charting.DataChart"]=true;
dojo.provide("dojox.charting.DataChart");
dojo.require("dojox.charting.Chart2D");
dojo.require("dojox.charting.themes.PlotKit.blue");
dojo.experimental("dojox.charting.DataChart");
(function(){
var _1={vertical:true,min:0,max:10,majorTickStep:5,minorTickStep:1,natural:false,stroke:"black",majorTick:{stroke:"black",length:8},minorTick:{stroke:"gray",length:2},majorLabels:true};
var _2={natural:true,majorLabels:true,includeZero:false,majorTickStep:1,majorTick:{stroke:"black",length:8},fixUpper:"major",stroke:"black",htmlLabels:true,from:1};
var _3={markers:true,tension:2,gap:2};
dojo.declare("dojox.charting.DataChart",[dojox.charting.Chart2D],{scroll:true,comparative:false,query:"*",queryOptions:"",fieldName:"value",chartTheme:dojox.charting.themes.PlotKit.blue,displayRange:0,stretchToFit:true,minWidth:200,minHeight:100,showing:true,label:"name",constructor:function(_4,_5){
this.domNode=dojo.byId(_4);
dojo.mixin(this,_5);
this.xaxis=dojo.mixin(dojo.mixin({},_2),_5.xaxis);
if(this.xaxis.labelFunc=="seriesLabels"){
this.xaxis.labelFunc=dojo.hitch(this,"seriesLabels");
}
this.yaxis=dojo.mixin(dojo.mixin({},_1),_5.yaxis);
if(this.yaxis.labelFunc=="seriesLabels"){
this.yaxis.labelFunc=dojo.hitch(this,"seriesLabels");
}
this.convertLabels(this.yaxis);
this.convertLabels(this.xaxis);
this.onSetItems={};
this.onSetInterval=0;
this.dataLength=0;
this.seriesData={};
this.seriesDataBk={};
this.firstRun=true;
this.dataOffset=0;
this.chartTheme.plotarea.stroke={color:"gray",width:3};
this.setTheme(this.chartTheme);
if(this.displayRange){
this.stretchToFit=false;
}
if(!this.stretchToFit){
this.xaxis.to=this.displayRange;
}
this.addAxis("x",this.xaxis);
this.addAxis("y",this.yaxis);
_3.type=_5.type||"Markers";
this.addPlot("default",dojo.mixin(_3,_5.chartPlot));
this.addPlot("grid",dojo.mixin(_5.grid||{},{type:"Grid",hMinorLines:true}));
if(this.showing){
this.render();
}
if(_5.store){
this.setStore(_5.store,_5.query,_5.fieldName,_5.queryOptions);
}
},setStore:function(_6,_7,_8,_9){
this.firstRun=true;
this.store=_6||this.store;
this.query=_7||this.query;
this.fieldName=_8||this.fieldName;
this.label=this.store.getLabelAttributes();
this.queryOptions=_9||_9;
this.fetch();
dojo.connect(this.store,"onSet",this,"onSet");
dojo.connect(this.store,"onError",this,"onError");
},show:function(){
if(!this.showing){
dojo.style(this.domNode,"display","");
this.showing=true;
this.render();
}
},hide:function(){
if(this.showing){
dojo.style(this.domNode,"display","none");
this.showing=false;
}
},onSet:function(_a){
var nm=this.getProperty(_a,this.label);
if(nm in this.runs||this.comparative){
clearTimeout(this.onSetInterval);
if(!this.onSetItems[nm]){
this.onSetItems[nm]=_a;
}
this.onSetInterval=setTimeout(dojo.hitch(this,function(){
clearTimeout(this.onSetInterval);
var _c=[];
for(var nm in this.onSetItems){
_c.push(this.onSetItems[nm]);
}
this.onData(_c);
this.onSetItems={};
}),200);
}
},onError:function(_e){
console.error(_e);
},onDataReceived:function(_f){
},getProperty:function(_10,_11){
if(_11==this.label){
return this.store.getLabel(_10);
}
if(_11=="id"){
return this.store.getIdentity(_10);
}
var _12=this.store.getValues(_10,_11);
if(_12.length<2){
_12=this.store.getValue(_10,_11);
}
return _12;
},onData:function(_13){
if(!_13.length){
return;
}
if(this.items&&this.items.length!=_13.length){
dojo.forEach(_13,function(m){
var id=this.getProperty(m,"id");
dojo.forEach(this.items,function(m2,i){
if(this.getProperty(m2,"id")==id){
this.items[i]=m2;
}
},this);
},this);
_13=this.items;
}
if(this.stretchToFit){
this.displayRange=_13.length;
}
this.onDataReceived(_13);
this.items=_13;
if(this.comparative){
var nm="default";
this.seriesData[nm]=[];
this.seriesDataBk[nm]=[];
dojo.forEach(_13,function(m,i){
var _1b=this.getProperty(m,this.fieldName);
this.seriesData[nm].push(_1b);
},this);
}else{
dojo.forEach(_13,function(m,i){
var nm=this.store.getLabel(m);
if(!this.seriesData[nm]){
this.seriesData[nm]=[];
this.seriesDataBk[nm]=[];
}
var _1f=this.getProperty(m,this.fieldName);
if(dojo.isArray(_1f)){
this.seriesData[nm]=_1f;
}else{
if(!this.scroll){
var ar=dojo.map(new Array(i+1),function(){
return 0;
});
ar.push(Number(_1f));
this.seriesData[nm]=ar;
}else{
if(this.seriesDataBk[nm].length>this.seriesData[nm].length){
this.seriesData[nm]=this.seriesDataBk[nm];
}
this.seriesData[nm].push(Number(_1f));
}
this.seriesDataBk[nm].push(Number(_1f));
}
},this);
}
var _21;
if(this.firstRun){
this.firstRun=false;
for(nm in this.seriesData){
this.addSeries(nm,this.seriesData[nm]);
_21=this.seriesData[nm];
}
}else{
for(nm in this.seriesData){
_21=this.seriesData[nm];
if(this.scroll&&_21.length>this.displayRange){
this.dataOffset=_21.length-this.displayRange-1;
_21=_21.slice(_21.length-this.displayRange,_21.length);
}
this.updateSeries(nm,_21);
}
}
this.dataLength=_21.length;
if(this.showing){
this.render();
}
},fetch:function(){
if(!this.store){
return;
}
this.store.fetch({query:this.query,queryOptions:this.queryOptions,start:this.start,count:this.count,sort:this.sort,onComplete:dojo.hitch(this,"onData"),onError:dojo.hitch(this,"onError")});
},convertLabels:function(_22){
if(!_22.labels||dojo.isObject(_22.labels[0])){
return null;
}
_22.labels=dojo.map(_22.labels,function(ele,i){
return {value:i,text:ele};
});
return null;
},seriesLabels:function(val){
val--;
if(this.series.length<1||(!this.comparative&&val>this.series.length)){
return "-";
}
if(this.comparative){
return this.store.getLabel(this.items[val]);
}else{
for(var i=0;i<this.series.length;i++){
if(this.series[i].data[val]>0){
return this.series[i].name;
}
}
}
return "-";
},resizeChart:function(dim){
var w=Math.max(dim.w,this.minWidth);
var h=Math.max(dim.h,this.minHeight);
this.resize(w,h);
}});
})();
}
