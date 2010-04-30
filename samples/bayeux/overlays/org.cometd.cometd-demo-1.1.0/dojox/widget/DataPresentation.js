/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dojox.widget.DataPresentation"]){
dojo._hasResource["dojox.widget.DataPresentation"]=true;
dojo.provide("dojox.widget.DataPresentation");
dojo.experimental("dojox.widget.DataPresentation");
dojo.require("dojox.grid.DataGrid");
dojo.require("dojox.charting.Chart2D");
dojo.require("dojox.charting.widget.Legend");
dojo.require("dojox.charting.action2d.Tooltip");
dojo.require("dojox.charting.action2d.Highlight");
dojo.require("dojo.colors");
dojo.require("dojo.data.ItemFileWriteStore");
(function(){
var _1=function(_2,_3,_4,_5,_6){
var _7=[],_8=_3;
_7[0]={value:0,text:""};
var _9=_2.slice(0);
if(_4){
_9.reverse();
}
var _a=_9.length;
if((_5!=="ClusteredBars")&&(_5!=="StackedBars")){
var _b=_6.offsetWidth;
var _c=(""+_9[0]).length*_9.length*7;
if(_8==1){
for(var z=1;z<500;++z){
if((_c/z)<_b){
break;
}
++_8;
}
}
}
for(var i=0;i<_a;i++){
if(i%_8==0){
_7.push({value:(i+1),text:_9[i]});
}else{
_7.push({value:(i+1),text:""});
}
}
_7.push({value:(_a+1),text:""});
return _7;
};
var _d=function(_e,_f){
var _10={vertical:false,labels:_f,min:0,max:_f.length-1,majorTickStep:1,minorTickStep:1};
if((_e==="ClusteredBars")||(_e==="StackedBars")){
_10.vertical=true;
}
if((_e==="Lines")||(_e==="Areas")||(_e==="StackedAreas")){
_10.min++;
_10.max--;
}
return _10;
};
var _11=function(_12,_13,_14,_15){
var _16={vertical:true,fixLower:"major",fixUpper:"major",natural:true};
if(_13==="secondary"){
_16.leftBottom=false;
}
if((_12==="ClusteredBars")||(_12==="StackedBars")){
_16.vertical=false;
}
if(_14==_15){
_16.min=_14-1;
_16.max=_15+1;
}
return _16;
};
var _17=function(_18,_19,_1a){
var _1b={type:_18,hAxis:"independent",vAxis:"dependent-"+_19,gap:4,lines:false,areas:false,markers:false};
if((_18==="ClusteredBars")||(_18==="StackedBars")){
_1b.hAxis=_1b.vAxis;
_1b.vAxis="independent";
}
if((_18==="Lines")||(_18==="Hybrid-Lines")||(_18==="Areas")||(_18==="StackedAreas")){
_1b.lines=true;
}
if((_18==="Areas")||(_18==="StackedAreas")){
_1b.areas=true;
}
if(_18==="Lines"){
_1b.markers=true;
}
if(_18==="Hybrid-Lines"){
_1b.shadows={dx:2,dy:2,dw:2};
_1b.type="Lines";
}
if(_18==="Hybrid-ClusteredColumns"){
_1b.type="ClusteredColumns";
}
if(_1a){
_1b.animate=_1a;
}
return _1b;
};
var _1c=function(_1d,_1e,_1f,_20,_21,_22,_23,_24,_25,_26){
var _27=_1e;
if(!_27){
_1d.innerHTML="";
_27=new dojox.charting.Chart2D(_1d);
}
if(_23){
_23._clone=function(){
var _28=new dojox.charting.Theme({chart:this.chart,plotarea:this.plotarea,axis:this.axis,series:this.series,marker:this.marker,antiAlias:this.antiAlias,assignColors:this.assignColors,assignMarkers:this.assigneMarkers,colors:dojo.delegate(this.colors)});
_28.markers=this.markers;
_28._buildMarkerArray();
return _28;
};
_27.setTheme(_23);
}
var _29=_1(_24.series_data[0],_22,_20,_1f,_1d);
var _2a={};
var _2b=null;
var _2c=null;
var _2d=_24.series_name.length;
for(var i=0;i<_2d;i++){
if(_24.series_chart[i]&&(_24.series_data[i].length>0)){
var _2e=_1f;
var _2f=_24.series_axis[i];
if(_2e=="Hybrid"){
if(_24.series_charttype[i]=="line"){
_2e="Hybrid-Lines";
}else{
_2e="Hybrid-ClusteredColumns";
}
}
if(!_2a[_2f]){
_2a[_2f]={};
}
if(!_2a[_2f][_2e]){
var _30=_2f+"-"+_2e;
_27.addPlot(_30,_17(_2e,_2f,_21));
new dojox.charting.action2d.Tooltip(_27,_30);
if((_2e!=="Lines")&&(_2e!=="Hybrid-Lines")){
new dojox.charting.action2d.Highlight(_27,_30);
}
_2a[_2f][_2e]=true;
}
var _31=[];
var _32=_24.series_data[i].length;
for(var j=0;j<_32;j++){
var val=_24.series_data[i][j];
_31.push(val);
if(_2b===null||val>_2b){
_2b=val;
}
if(_2c===null||val<_2c){
_2c=val;
}
}
if(_20){
_31.reverse();
}
var _33={plot:_2f+"-"+_2e};
if(_24.series_linestyle[i]){
_33.stroke={style:_24.series_linestyle[i]};
}
_27.addSeries(_24.series_name[i],_31,_33);
}
}
_27.addAxis("independent",_d(_1f,_29));
_27.addAxis("dependent-primary",_11(_1f,"primary",_2c,_2b));
_27.addAxis("dependent-secondary",_11(_1f,"secondary",_2c,_2b));
_27.render();
return _27;
};
var _34=function(_35,_36,_37,_38){
var _39=_36;
if(!_39){
if(_38){
_39=new dojox.charting.widget.Legend({chart:_37,horizontal:false},_35);
}else{
_39=new dojox.charting.widget.Legend({chart:_37,vertical:false},_35);
}
}
return _39;
};
var _3a=function(_3b,_3c,_3d,_3e,_3f){
var _40=_3c||new dojox.grid.DataGrid({},_3b);
_40.startup();
_40.setStore(_3d,_3e,_3f);
var _41=[];
for(var ser=0;ser<_3d.series_name.length;ser++){
if(_3d.series_grid[ser]&&(_3d.series_data[ser].length>0)){
_41.push({field:"data."+ser,name:_3d.series_name[ser],width:"auto",formatter:_3d.series_gridformatter[ser]});
}
}
_40.setStructure(_41);
_40.render();
return _40;
};
var _42=function(_43,_44){
if(_44.title){
_43.innerHTML=_44.title;
}
};
var _45=function(_46,_47){
if(_47.footer){
_46.innerHTML=_47.footer;
}
};
var _48=function(_49,_4a){
var _4b=_49;
if(_4a){
var _4c=_4a.split(/[.\[\]]+/);
for(var _4d in _4c){
if(_4b){
_4b=_4b[_4c[_4d]];
}
}
}
return _4b;
};
dojo.declare("dojox.widget.DataPresentation",null,{type:"chart",chartType:"clusteredBars",reverse:false,animate:null,labelMod:1,legendVertical:false,constructor:function(_4e,_4f){
dojo.mixin(this,_4f);
this.domNode=dojo.byId(_4e);
this[this.type+"Node"]=this.domNode;
if(typeof this.theme=="string"){
this.theme=dojo.getObject(this.theme);
}
this.chartNode=dojo.byId(this.chartNode);
this.legendNode=dojo.byId(this.legendNode);
this.gridNode=dojo.byId(this.gridNode);
this.titleNode=dojo.byId(this.titleNode);
this.footerNode=dojo.byId(this.footerNode);
if(this.url){
this.setURL(null,this.refreshInterval);
}else{
if(this.data){
this.setData(null,this.refreshInterval);
}else{
this.setStore();
}
}
},setURL:function(url,_50){
if(_50){
this.cancelRefresh();
}
this.url=url||this.url;
this.refreshInterval=_50||this.refreshInterval;
var me=this;
dojo.xhrGet({url:this.url,handleAs:"json-comment-optional",load:function(_51,_52){
me.setData(_51);
},error:function(xhr,_53){
if(me.urlError&&(typeof me.urlError=="function")){
me.urlError(xhr,_53);
}
}});
if(_50&&(this.refreshInterval>0)){
this.refreshIntervalPending=setInterval(function(){
me.setURL();
},this.refreshInterval);
}
},setData:function(_54,_55){
if(_55){
this.cancelRefresh();
}
this.data=_54||this.data;
this.refreshInterval=_55||this.refreshInterval;
var _56=(typeof this.series=="function")?this.series(this.data):this.series;
var _57=[],_58=[],_59=[],_5a=[],_5b=[],_5c=[],_5d=[],_5e=[],_5f=[],_60=0;
for(var ser=0;ser<_56.length;ser++){
_57[ser]=_48(this.data,_56[ser].datapoints);
if(_57[ser]&&(_57[ser].length>_60)){
_60=_57[ser].length;
}
_58[ser]=[];
_59[ser]=_56[ser].name||(_56[ser].namefield?_48(this.data,_56[ser].namefield):null)||("series "+ser);
_5a[ser]=(_56[ser].chart!==false);
_5b[ser]=_56[ser].charttype||"bar";
_5c[ser]=_56[ser].linestyle;
_5d[ser]=_56[ser].axis||"primary";
_5e[ser]=(_56[ser].grid!==false);
_5f[ser]=_56[ser].gridformatter;
}
var _61,_62,_63,_64;
var _65=[];
for(_61=0;_61<_60;_61++){
_62={index:_61};
for(ser=0;ser<_56.length;ser++){
if(_57[ser]&&(_57[ser].length>_61)){
_63=_48(_57[ser][_61],_56[ser].field);
if(_5a[ser]){
_64=parseFloat(_63);
if(!isNaN(_64)){
_63=_64;
}
}
_62["data."+ser]=_63;
_58[ser].push(_63);
}
}
_65.push(_62);
}
if(_60<=0){
_65.push({index:0});
}
var _66=new dojo.data.ItemFileWriteStore({data:{identifier:"index",items:_65}});
if(this.data.title){
_66.title=this.data.title;
}
if(this.data.footer){
_66.footer=this.data.footer;
}
_66.series_data=_58;
_66.series_name=_59;
_66.series_chart=_5a;
_66.series_charttype=_5b;
_66.series_linestyle=_5c;
_66.series_axis=_5d;
_66.series_grid=_5e;
_66.series_gridformatter=_5f;
this.setPreparedStore(_66);
if(_55&&(this.refreshInterval>0)){
var me=this;
this.refreshIntervalPending=setInterval(function(){
me.setData();
},this.refreshInterval);
}
},refresh:function(){
if(this.url){
this.setURL(this.url,this.refreshInterval);
}else{
if(this.data){
this.setData(this.data,this.refreshInterval);
}
}
},cancelRefresh:function(){
if(this.refreshIntervalPending){
clearInterval(this.refreshIntervalPending);
this.refreshIntervalPending=undefined;
}
},setStore:function(_67,_68,_69){
this.setPreparedStore(_67,_68,_69);
},setPreparedStore:function(_6a,_6b,_6c){
this.preparedstore=_6a||this.store;
this.query=_6b||this.query;
this.queryOptions=_6c||this.queryOptions;
if(this.preparedstore){
if(this.chartNode){
this.chartWidget=_1c(this.chartNode,this.chartWidget,this.chartType,this.reverse,this.animate,this.labelMod,this.theme,this.preparedstore,this.query,this,_6c);
}
if(this.legendNode){
this.legendWidget=_34(this.legendNode,this.legendWidget,this.chartWidget,this.legendVertical);
}
if(this.gridNode){
this.gridWidget=_3a(this.gridNode,this.gridWidget,this.preparedstore,this.query,this.queryOptions);
}
if(this.titleNode){
_42(this.titleNode,this.preparedstore);
}
if(this.footerNode){
_45(this.footerNode,this.preparedstore);
}
}
},getChartWidget:function(){
return this.chartWidget;
},getGridWidget:function(){
return this.gridWidget;
},destroy:function(){
this.cancelRefresh();
if(this.chartWidget){
this.chartWidget.destroy();
this.chartWidget=undefined;
}
if(this.legendWidget){
this.legendWidget=undefined;
}
if(this.gridWidget){
this.gridWidget=undefined;
}
if(this.chartNode){
this.chartNode.innerHTML="";
}
if(this.legendNode){
this.legendNode.innerHTML="";
}
if(this.gridNode){
this.gridNode.innerHTML="";
}
if(this.titleNode){
this.titleNode.innerHTML="";
}
if(this.footerNode){
this.footerNode.innerHTML="";
}
}});
})();
}
