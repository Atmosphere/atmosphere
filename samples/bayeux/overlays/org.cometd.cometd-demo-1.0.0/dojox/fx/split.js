/*
	Copyright (c) 2004-2009, The Dojo Foundation All Rights Reserved.
	Available via Academic Free License >= 2.1 OR the modified BSD license.
	see: http://dojotoolkit.org/license for details
*/


if(!dojo._hasResource["dojox.fx.split"]){
dojo._hasResource["dojox.fx.split"]=true;
dojo.provide("dojox.fx.split");
dojo.require("dojo.fx");
dojo.require("dojo.fx.easing");
dojo.mixin(dojox.fx,{_split:function(_1){
_1.rows=_1.rows||3;
_1.columns=_1.columns||3;
_1.duration=_1.duration||1000;
var _2=_1.node=dojo.byId(_1.node),_3=dojo.coords(_2,true),_4=Math.ceil(_3.h/_1.rows),_5=Math.ceil(_3.w/_1.columns),_6=dojo.create(_2.tagName),_7=[],_8=dojo.create(_2.tagName),_9;
dojo.style(_6,{position:"absolute",padding:"0",margin:"0",border:"none",top:_3.y+"px",left:_3.x+"px",height:_3.h+"px",width:_3.w+"px",background:"none",overflow:_1.crop?"hidden":"visible"});
_2.parentNode.appendChild(_6);
dojo.style(_8,{position:"absolute",border:"none",padding:"0",margin:"0",height:_4+"px",width:_5+"px",overflow:"hidden"});
for(var y=0;y<_1.rows;y++){
for(var x=0;x<_1.columns;x++){
_9=dojo.clone(_8);
var _c=dojo.clone(_2);
_c.style.filter="";
dojo.style(_9,{border:"none",overflow:"hidden",top:(_4*y)+"px",left:(_5*x)+"px"});
dojo.style(_c,{position:"static",opacity:"1",marginTop:(-y*_4)+"px",marginLeft:(-x*_5)+"px"});
_9.appendChild(_c);
_6.appendChild(_9);
var _d=_1.pieceAnimation(_9,x,y,_3);
if(dojo.isArray(_d)){
_7=_7.concat(_d);
}else{
_7.push(_d);
}
}
}
var _e=dojo.fx.combine(_7);
dojo.connect(_e,"onEnd",_e,function(){
_6.parentNode.removeChild(_6);
});
if(_1.onPlay){
dojo.connect(_e,"onPlay",_e,_1.onPlay);
}
if(_1.onEnd){
dojo.connect(_e,"onEnd",_e,_1.onEnd);
}
return _e;
},explode:function(_f){
var _10=_f.node=dojo.byId(_f.node);
_f.rows=_f.rows||3;
_f.columns=_f.columns||3;
_f.distance=_f.distance||1;
_f.duration=_f.duration||1000;
_f.random=_f.random||0;
if(!_f.fade){
_f.fade=true;
}
if(typeof _f.sync=="undefined"){
_f.sync=true;
}
_f.random=Math.abs(_f.random);
_f.pieceAnimation=function(_11,x,y,_14){
var _15=_14.h/_f.rows,_16=_14.w/_f.columns,_17=_f.distance*2,_18=_f.duration,ps=_11.style,_1a=parseInt(ps.top),_1b=parseInt(ps.left),_1c=0,_1d=0,_1e=0;
if(_f.random){
var _1f=(Math.random()*_f.random)+Math.max(1-_f.random,0);
_17*=_1f;
_18*=_1f;
_1c=((_f.unhide&&_f.sync)||(!_f.unhide&&!_f.sync))?(_f.duration-_18):0;
_1d=Math.random()-0.5;
_1e=Math.random()-0.5;
}
var _20=((_14.h-_15)/2-_15*y),_21=((_14.w-_16)/2-_16*x),_22=Math.sqrt(Math.pow(_21,2)+Math.pow(_20,2)),_23=parseInt(_1a-_20*_17+_22*_1e),_24=parseInt(_1b-_21*_17+_22*_1d);
var _25=dojo.animateProperty({node:_11,duration:_18,delay:_1c,easing:(_f.easing||(_f.unhide?dojo.fx.easing.sinOut:dojo.fx.easing.circOut)),beforeBegin:(_f.unhide?function(){
if(_f.fade){
dojo.style(_11,{opacity:"0"});
}
ps.top=_23+"px";
ps.left=_24+"px";
}:undefined),properties:{top:(_f.unhide?{start:_23,end:_1a}:{start:_1a,end:_23}),left:(_f.unhide?{start:_24,end:_1b}:{start:_1b,end:_24})}});
if(_f.fade){
var _26=dojo.animateProperty({node:_11,duration:_18,delay:_1c,easing:(_f.fadeEasing||dojo.fx.easing.quadOut),properties:{opacity:(_f.unhide?{start:"0",end:"1"}:{start:"1",end:"0"})}});
return (_f.unhide?[_26,_25]:[_25,_26]);
}else{
return _25;
}
};
var _27=dojox.fx._split(_f);
if(_f.unhide){
dojo.connect(_27,"onEnd",null,function(){
dojo.style(_10,{opacity:"1"});
});
}else{
dojo.connect(_27,"onPlay",null,function(){
dojo.style(_10,{opacity:"0"});
});
}
return _27;
},converge:function(_28){
_28.unhide=true;
return dojox.fx.explode(_28);
},disintegrate:function(_29){
var _2a=_29.node=dojo.byId(_29.node);
_29.rows=_29.rows||5;
_29.columns=_29.columns||5;
_29.duration=_29.duration||1500;
_29.interval=_29.interval||_29.duration/(_29.rows+_29.columns*2);
_29.distance=_29.distance||1.5;
_29.random=_29.random||0;
if(typeof _29.fade=="undefined"){
_29.fade=true;
}
var _2b=Math.abs(_29.random),_2c=_29.duration-(_29.rows+_29.columns)*_29.interval;
_29.pieceAnimation=function(_2d,x,y,_30){
var _31=Math.random()*(_29.rows+_29.columns)*_29.interval,ps=_2d.style,_33=(_29.reverseOrder||_29.distance<0)?((x+y)*_29.interval):(((_29.rows+_29.columns)-(x+y))*_29.interval),_34=_31*_2b+Math.max(1-_2b,0)*_33,_35={};
if(_29.unhide){
_35.top={start:(parseInt(ps.top)-_30.h*_29.distance),end:parseInt(ps.top)};
if(_29.fade){
_35.opacity={start:"0",end:"1"};
}
}else{
_35.top={end:(parseInt(ps.top)+_30.h*_29.distance)};
if(_29.fade){
_35.opacity={end:"0"};
}
}
var _36=dojo.animateProperty({node:_2d,duration:_2c,delay:_34,easing:(_29.easing||(_29.unhide?dojo.fx.easing.sinIn:dojo.fx.easing.circIn)),properties:_35,beforeBegin:(_29.unhide?function(){
if(_29.fade){
dojo.style(_2d,{opacity:"0"});
}
ps.top=_35.top.start+"px";
}:undefined)});
return _36;
};
var _37=dojox.fx._split(_29);
if(_29.unhide){
dojo.connect(_37,"onEnd",_37,function(){
dojo.style(_2a,{opacity:"1"});
});
}else{
dojo.connect(_37,"onPlay",_37,function(){
dojo.style(_2a,{opacity:"0"});
});
}
return _37;
},build:function(_38){
_38.unhide=true;
return dojox.fx.disintegrate(_38);
},shear:function(_39){
var _3a=_39.node=dojo.byId(_39.node);
_39.rows=_39.rows||6;
_39.columns=_39.columns||6;
_39.duration=_39.duration||1000;
_39.interval=_39.interval||0;
_39.distance=_39.distance||1;
_39.random=_39.random||0;
if(typeof (_39.fade)=="undefined"){
_39.fade=true;
}
var _3b=Math.abs(_39.random),_3c=(_39.duration-(_39.rows+_39.columns)*Math.abs(_39.interval));
_39.pieceAnimation=function(_3d,x,y,_40){
var _41=!(x%2),_42=!(y%2),_43=Math.random()*_3c,_44=(_39.reverseOrder)?(((_39.rows+_39.columns)-(x+y))*_39.interval):((x+y)*_39.interval),_45=_43*_3b+Math.max(1-_3b,0)*_44,_46={},ps=_3d.style;
if(_39.fade){
_46.opacity=(_39.unhide?{start:"0",end:"1"}:{end:"0"});
}
if(_39.columns==1){
_41=_42;
}else{
if(_39.rows==1){
_42=!_41;
}
}
var _48=parseInt(ps.left),top=parseInt(ps.top),_4a=_39.distance*_40.w,_4b=_39.distance*_40.h;
if(_39.unhide){
if(_41==_42){
_46.left=_41?{start:(_48-_4a),end:_48}:{start:(_48+_4a),end:_48};
}else{
_46.top=_41?{start:(top+_4b),end:top}:{start:(top-_4b),end:top};
}
}else{
if(_41==_42){
_46.left=_41?{end:(_48-_4a)}:{end:(_48+_4a)};
}else{
_46.top=_41?{end:(top+_4b)}:{end:(top-_4b)};
}
}
var _4c=dojo.animateProperty({node:_3d,duration:_3c,delay:_45,easing:(_39.easing||dojo.fx.easing.sinInOut),properties:_46,beforeBegin:(_39.unhide?function(){
if(_39.fade){
ps.opacity="0";
}
if(_41==_42){
ps.left=_46.left.start+"px";
}else{
ps.top=_46.top.start+"px";
}
}:undefined)});
return _4c;
};
var _4d=dojox.fx._split(_39);
if(_39.unhide){
dojo.connect(_4d,"onEnd",_4d,function(){
dojo.style(_3a,{opacity:"1"});
});
}else{
dojo.connect(_4d,"onPlay",_4d,function(){
dojo.style(_3a,{opacity:"0"});
});
}
return _4d;
},unShear:function(_4e){
_4e.unhide=true;
return dojox.fx.shear(_4e);
},pinwheel:function(_4f){
var _50=_4f.node=dojo.byId(_4f.node);
_4f.rows=_4f.rows||4;
_4f.columns=_4f.columns||4;
_4f.duration=_4f.duration||1000;
_4f.interval=_4f.interval||0;
_4f.distance=_4f.distance||1;
_4f.random=_4f.random||0;
if(typeof _4f.fade=="undefined"){
_4f.fade=true;
}
var _51=(_4f.duration-(_4f.rows+_4f.columns)*Math.abs(_4f.interval));
_4f.pieceAnimation=function(_52,x,y,_55){
var _56=_55.h/_4f.rows,_57=_55.w/_4f.columns,_58=!(x%2),_59=!(y%2),_5a=Math.random()*_51,_5b=(_4f.interval<0)?(((_4f.rows+_4f.columns)-(x+y))*_4f.interval*-1):((x+y)*_4f.interval),_5c=_5a*_4f.random+Math.max(1-_4f.random,0)*_5b,_5d={},ps=_52.style;
if(_4f.fade){
_5d.opacity=(_4f.unhide?{start:0,end:1}:{end:0});
}
if(_4f.columns==1){
_58=!_59;
}else{
if(_4f.rows==1){
_59=_58;
}
}
var _5f=parseInt(ps.left),top=parseInt(ps.top);
if(_58){
if(_59){
_5d.top=_4f.unhide?{start:top+_56*_4f.distance,end:top}:{start:top,end:top+_56*_4f.distance};
}else{
_5d.left=_4f.unhide?{start:_5f+_57*_4f.distance,end:_5f}:{start:_5f,end:_5f+_57*_4f.distance};
}
}
if(_58!=_59){
_5d.width=_4f.unhide?{start:_57*(1-_4f.distance),end:_57}:{start:_57,end:_57*(1-_4f.distance)};
}else{
_5d.height=_4f.unhide?{start:_56*(1-_4f.distance),end:_56}:{start:_56,end:_56*(1-_4f.distance)};
}
var _61=dojo.animateProperty({node:_52,duration:_51,delay:_5c,easing:(_4f.easing||dojo.fx.easing.sinInOut),properties:_5d,beforeBegin:(_4f.unhide?function(){
if(_4f.fade){
dojo.style(_52,"opacity",0);
}
if(_58){
if(_59){
ps.top=(top+_56*(1-_4f.distance))+"px";
}else{
ps.left=(_5f+_57*(1-_4f.distance))+"px";
}
}else{
ps.left=_5f+"px";
ps.top=top+"px";
}
if(_58!=_59){
ps.width=(_57*(1-_4f.distance))+"px";
}else{
ps.height=(_56*(1-_4f.distance))+"px";
}
}:undefined)});
return _61;
};
var _62=dojox.fx._split(_4f);
if(_4f.unhide){
dojo.connect(_62,"onEnd",_62,function(){
dojo.style(_50,{opacity:"1"});
});
}else{
dojo.connect(_62,"play",_62,function(){
dojo.style(_50,{opacity:"0"});
});
}
return _62;
},unPinwheel:function(_63){
_63.unhide=true;
return dojox.fx.pinwheel(_63);
},blockFadeOut:function(_64){
var _65=_64.node=dojo.byId(_64.node);
_64.rows=_64.rows||5;
_64.columns=_64.columns||5;
_64.duration=_64.duration||1000;
_64.interval=_64.interval||_64.duration/(_64.rows+_64.columns*2);
_64.random=_64.random||0;
var _66=Math.abs(_64.random),_67=_64.duration-(_64.rows+_64.columns)*_64.interval;
_64.pieceAnimation=function(_68,x,y,_6b){
var _6c=Math.random()*_64.duration,_6d=(_64.reverseOrder)?(((_64.rows+_64.columns)-(x+y))*Math.abs(_64.interval)):((x+y)*_64.interval),_6e=_6c*_66+Math.max(1-_66,0)*_6d,_6f=dojo.animateProperty({node:_68,duration:_67,delay:_6e,easing:(_64.easing||dojo.fx.easing.sinInOut),properties:{opacity:(_64.unhide?{start:"0",end:"1"}:{start:"1",end:"0"})},beforeBegin:(_64.unhide?function(){
dojo.style(_68,{opacity:"0"});
}:function(){
_68.style.filter="";
})});
return _6f;
};
var _70=dojox.fx._split(_64);
if(_64.unhide){
dojo.connect(_70,"onEnd",_70,function(){
dojo.style(_65,{opacity:"1"});
});
}else{
dojo.connect(_70,"onPlay",_70,function(){
dojo.style(_65,{opacity:"0"});
});
}
return _70;
},blockFadeIn:function(_71){
_71.unhide=true;
return dojox.fx.blockFadeOut(_71);
}});
}
