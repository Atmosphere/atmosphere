/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dojox.fx.flip"]){
dojo._hasResource["dojox.fx.flip"]=true;
dojo.provide("dojox.fx.flip");
dojo.experimental("dojox.fx.flip");
dojo.require("dojo.fx");
(function(){
var _1="border",_2="Width",_3="Height",_4="Top",_5="Right",_6="Left",_7="Bottom";
dojox.fx.flip=function(_8){
var _9=dojo.create("div"),_a=_8.node=dojo.byId(_8.node),s=_a.style,_b=null,hs=null,pn=null,_c=_8.lightColor||"#dddddd",_d=_8.darkColor||"#555555",_e=dojo.style(_a,"backgroundColor"),_f=_8.endColor||_e,_10={},_11=[],_12=_8.duration?_8.duration/2:250,dir=_8.dir||"left",_13=0.9,_14="transparent",_15=_8.whichAnim,_16=_8.axis||"center",_17=_8.depth;
var _18=function(_19){
return ((new dojo.Color(_19)).toHex()==="#000000")?"#000001":_19;
};
if(dojo.isIE<7){
_f=_18(_f);
_c=_18(_c);
_d=_18(_d);
_e=_18(_e);
_14="black";
_9.style.filter="chroma(color='#000000')";
}
var _1a=(function(n){
return function(){
var ret=dojo.coords(n,true);
_b={top:ret.y,left:ret.x,width:ret.w,height:ret.h};
};
})(_a);
_1a();
hs={position:"absolute",top:_b["top"]+"px",left:_b["left"]+"px",height:"0",width:"0",zIndex:_8.zIndex||(s.zIndex||0),border:"0 solid "+_14,fontSize:"0",visibility:"hidden"};
var _1b=[{},{top:_b["top"],left:_b["left"]}];
var _1c={left:[_6,_5,_4,_7,_2,_3,"end"+_3+"Min",_6,"end"+_3+"Max"],right:[_5,_6,_4,_7,_2,_3,"end"+_3+"Min",_6,"end"+_3+"Max"],top:[_4,_7,_6,_5,_3,_2,"end"+_2+"Min",_4,"end"+_2+"Max"],bottom:[_7,_4,_6,_5,_3,_2,"end"+_2+"Min",_4,"end"+_2+"Max"]};
pn=_1c[dir];
if(typeof _17!="undefined"){
_17=Math.max(0,Math.min(1,_17))/2;
_13=0.4+(0.5-_17);
}else{
_13=Math.min(0.9,Math.max(0.4,_b[pn[5].toLowerCase()]/_b[pn[4].toLowerCase()]));
}
var p0=_1b[0];
for(var i=4;i<6;i++){
if(_16=="center"||_16=="cube"){
_b["end"+pn[i]+"Min"]=_b[pn[i].toLowerCase()]*_13;
_b["end"+pn[i]+"Max"]=_b[pn[i].toLowerCase()]/_13;
}else{
if(_16=="shortside"){
_b["end"+pn[i]+"Min"]=_b[pn[i].toLowerCase()];
_b["end"+pn[i]+"Max"]=_b[pn[i].toLowerCase()]/_13;
}else{
if(_16=="longside"){
_b["end"+pn[i]+"Min"]=_b[pn[i].toLowerCase()]*_13;
_b["end"+pn[i]+"Max"]=_b[pn[i].toLowerCase()];
}
}
}
}
if(_16=="center"){
p0[pn[2].toLowerCase()]=_b[pn[2].toLowerCase()]-(_b[pn[8]]-_b[pn[6]])/4;
}else{
if(_16=="shortside"){
p0[pn[2].toLowerCase()]=_b[pn[2].toLowerCase()]-(_b[pn[8]]-_b[pn[6]])/2;
}
}
_10[pn[5].toLowerCase()]=_b[pn[5].toLowerCase()]+"px";
_10[pn[4].toLowerCase()]="0";
_10[_1+pn[1]+_2]=_b[pn[4].toLowerCase()]+"px";
_10[_1+pn[1]+"Color"]=_e;
p0[_1+pn[1]+_2]=0;
p0[_1+pn[1]+"Color"]=_d;
p0[_1+pn[2]+_2]=p0[_1+pn[3]+_2]=_16!="cube"?(_b["end"+pn[5]+"Max"]-_b["end"+pn[5]+"Min"])/2:_b[pn[6]]/2;
p0[pn[7].toLowerCase()]=_b[pn[7].toLowerCase()]+_b[pn[4].toLowerCase()]/2+(_8.shift||0);
p0[pn[5].toLowerCase()]=_b[pn[6]];
var p1=_1b[1];
p1[_1+pn[0]+"Color"]={start:_c,end:_f};
p1[_1+pn[0]+_2]=_b[pn[4].toLowerCase()];
p1[_1+pn[2]+_2]=0;
p1[_1+pn[3]+_2]=0;
p1[pn[5].toLowerCase()]={start:_b[pn[6]],end:_b[pn[5].toLowerCase()]};
dojo.mixin(hs,_10);
dojo.style(_9,hs);
dojo.body().appendChild(_9);
var _1d=function(){
dojo.destroy(_9);
s.backgroundColor=_f;
s.visibility="visible";
};
if(_15=="last"){
for(i in p0){
p0[i]={start:p0[i]};
}
p0[_1+pn[1]+"Color"]={start:_d,end:_f};
p1=p0;
}
if(!_15||_15=="first"){
_11.push(dojo.animateProperty({node:_9,duration:_12,properties:p0}));
}
if(!_15||_15=="last"){
_11.push(dojo.animateProperty({node:_9,duration:_12,properties:p1,onEnd:_1d}));
}
dojo.connect(_11[0],"play",function(){
_9.style.visibility="visible";
s.visibility="hidden";
});
return dojo.fx.chain(_11);
};
dojox.fx.flipCube=function(_1e){
var _1f=[],mb=dojo.marginBox(_1e.node),_20=mb.w/2,_21=mb.h/2,_22={top:{pName:"height",args:[{whichAnim:"first",dir:"top",shift:-_21},{whichAnim:"last",dir:"bottom",shift:_21}]},right:{pName:"width",args:[{whichAnim:"first",dir:"right",shift:_20},{whichAnim:"last",dir:"left",shift:-_20}]},bottom:{pName:"height",args:[{whichAnim:"first",dir:"bottom",shift:_21},{whichAnim:"last",dir:"top",shift:-_21}]},left:{pName:"width",args:[{whichAnim:"first",dir:"left",shift:-_20},{whichAnim:"last",dir:"right",shift:_20}]}};
var d=_22[_1e.dir||"left"],p=d.args;
_1e.duration=_1e.duration?_1e.duration*2:500;
_1e.depth=0.8;
_1e.axis="cube";
for(var i=p.length-1;i>=0;i--){
dojo.mixin(_1e,p[i]);
_1f.push(dojox.fx.flip(_1e));
}
return dojo.fx.combine(_1f);
};
dojox.fx.flipPage=function(_23){
var n=_23.node,_24=dojo.coords(n,true),x=_24.x,y=_24.y,w=_24.w,h=_24.h,_25=dojo.style(n,"backgroundColor"),_26=_23.lightColor||"#dddddd",_27=_23.darkColor,_28=dojo.create("div"),_29=[],hn=[],dir=_23.dir||"right",pn={left:["left","right","x","w"],top:["top","bottom","y","h"],right:["left","left","x","w"],bottom:["top","top","y","h"]},_2a={right:[1,-1],left:[-1,1],top:[-1,1],bottom:[1,-1]};
dojo.style(_28,{position:"absolute",width:w+"px",height:h+"px",top:y+"px",left:x+"px",visibility:"hidden"});
var hs=[];
for(var i=0;i<2;i++){
var r=i%2,d=r?pn[dir][1]:dir,wa=r?"last":"first",_2b=r?_25:_26,_2c=r?_2b:_23.startColor||n.style.backgroundColor;
hn[i]=dojo.clone(_28);
var _2d=function(x){
return function(){
dojo.destroy(hn[x]);
};
}(i);
dojo.body().appendChild(hn[i]);
hs[i]={backgroundColor:r?_2c:_25};
hs[i][pn[dir][0]]=_24[pn[dir][2]]+_2a[dir][0]*i*_24[pn[dir][3]]+"px";
dojo.style(hn[i],hs[i]);
_29.push(dojox.fx.flip({node:hn[i],dir:d,axis:"shortside",depth:_23.depth,duration:_23.duration/2,shift:_2a[dir][i]*_24[pn[dir][3]]/2,darkColor:_27,lightColor:_26,whichAnim:wa,endColor:_2b}));
dojo.connect(_29[i],"onEnd",_2d);
}
return dojo.fx.chain(_29);
};
dojox.fx.flipGrid=function(_2e){
var _2f=_2e.rows||4,_30=_2e.cols||4,_31=[],_32=dojo.create("div"),n=_2e.node,_33=dojo.coords(n,true),x=_33.x,y=_33.y,nw=_33.w,nh=_33.h,w=_33.w/_30,h=_33.h/_2f,_34=[];
dojo.style(_32,{position:"absolute",width:w+"px",height:h+"px",backgroundColor:dojo.style(n,"backgroundColor")});
for(var i=0;i<_2f;i++){
var r=i%2,d=r?"right":"left",_35=r?1:-1;
var cn=dojo.clone(n);
dojo.style(cn,{position:"absolute",width:nw+"px",height:nh+"px",top:y+"px",left:x+"px",clip:"rect("+i*h+"px,"+nw+"px,"+nh+"px,0)"});
dojo.body().appendChild(cn);
_31[i]=[];
for(var j=0;j<_30;j++){
var hn=dojo.clone(_32),l=r?j:_30-(j+1);
var _36=function(xn,_37,_38){
return function(){
if(!(_37%2)){
dojo.style(xn,{clip:"rect("+_37*h+"px,"+(nw-(_38+1)*w)+"px,"+((_37+1)*h)+"px,0px)"});
}else{
dojo.style(xn,{clip:"rect("+_37*h+"px,"+nw+"px,"+((_37+1)*h)+"px,"+((_38+1)*w)+"px)"});
}
};
}(cn,i,j);
dojo.body().appendChild(hn);
dojo.style(hn,{left:x+l*w+"px",top:y+i*h+"px",visibility:"hidden"});
var a=dojox.fx.flipPage({node:hn,dir:d,duration:_2e.duration||900,shift:_35*w/2,depth:0.2,darkColor:_2e.darkColor,lightColor:_2e.lightColor,startColor:_2e.startColor||_2e.node.style.backgroundColor}),_39=function(xn){
return function(){
dojo.destroy(xn);
};
}(hn);
dojo.connect(a,"play",this,_36);
dojo.connect(a,"play",this,_39);
_31[i].push(a);
}
_34.push(dojo.fx.chain(_31[i]));
}
dojo.connect(_34[0],"play",function(){
dojo.style(n,{visibility:"hidden"});
});
return dojo.fx.combine(_34);
};
})();
}
