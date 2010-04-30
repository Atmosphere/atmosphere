jmaki.namespace("jmaki.listeners");
   
jmaki.listeners.geocoderListener = function(coordinates) {
        var keys = jmaki.attributes.keys();
        // scan the widgets for all yahoo maps
        for (var l = 0; l < keys.length; l++) {
            if (jmaki.widgets.yahoo &&  jmaki.widgets.yahoo.map &&
                jmaki.widgets.yahoo.map.Widget &&
                jmaki.attributes.get(keys[l]) instanceof jmaki.widgets.yahoo.map.Widget) {
                var _map = jmaki.attributes.get(keys[l]).map;
                var centerPoint = new YGeoPoint(coordinates[0].latitude,coordinates[0].longitude);
                var marker = new YMarker(centerPoint);
                var txt = '<div style="width:160px;height:50px;"><b>' + coordinates[0].address + ' ' +
                    coordinates[0].city + ' ' +  coordinates[0].state + '</b></div>';
                marker.addAutoExpand(txt);
                _map.addOverlay(marker);
                _map.drawZoomAndCenter(centerPoint);
            } else if (typeof GLatLng != 'undefined' &&
                       jmaki.widgets.google &&
                       jmaki.widgets.google.map &&
                       jmaki.widgets.google.map.Widget &&
                       jmaki.attributes.get(keys[l]) instanceof jmaki.widgets.google.map.Widget) {
                // set the google map
                var _map = jmaki.attributes.get(keys[l]).map;
                var centerPoint = new GLatLng(coordinates[0].latitude,coordinates[0].longitude);
                _map.setCenter(centerPoint);
                var marker = new GMarker(centerPoint);
                _map.addOverlay(marker);
                var txt = '<div style="width:160px;height:50px;"><b>' + coordinates[0].address + ' ' +
                    coordinates[0].city + ' ' +  coordinates[0].state + '</b></div>';
                marker.openInfoWindowHtml(txt);               
            } 
        }
}
// add listerner mapping for geocoder
jmaki.subscribe(new RegExp("/jmaki/plotmap$"), "jmaki.listeners.geocoderListener");
// add listerner mapping for backward compatibility
jmaki.subscribe(new RegExp("/yahoo/geocoder$"), "jmaki.listeners.geocoderListener");


// sytem level filters
jmaki.namespace("jmaki.filters");

// convert an rss feed to the jMaki table format
jmaki.filters.tableFilter = function(input) {

    var _columns = [
            {title: 'Title'},
            //{title: 'URL'},
            {title: 'Date'},
            {title: 'Description'}
            ];
    var _rows = [];

    for (var _i=0; _i < input.channel.items.length;_i++) {
      var row = [
         input.channel.items[_i].title,
        // input.channel.items[_i].link,
         input.channel.items[_i].date,
         input.channel.items[_i].description
      ];
      _rows.push(row);
    }
    return {columns : _columns, rows : _rows};
}

// convert an rss feed to the jMaki Table Model format
jmaki.filters.tableModelFilter = function(input) {
    var _columns = [
            {label: 'Title', id : 'title'},
            //{lbael: 'URL' id : 'url'},
            {label: 'Date', id : 'date'},
            {label: 'Description', id : 'description'}
            ];
    var _rows = [];

    for (var _i=0; _i < input.channel.items.length;_i++) {
      var row = {
         title : input.channel.items[_i].title,
        //url : input.channel.items[_i].link,
         date : input.channel.items[_i].date,
         description : input.channel.items[_i].description
      };
      _rows.push(row);
    }
    return {type : 'jmakiModelData', columns : _columns, rows : _rows};
}

// convert an rss feed to the jMaki accordion format
jmaki.filters.accordionFilter = function(input) {

    var _rows = [];

    for (var _i=0; _i < input.channel.items.length;_i++) {
      var row = {
         label : input.channel.items[_i].title,
        // input.channel.items[_i].link,
        // input.channel.items[_i].date,
        content : input.channel.items[_i].description
      }
      _rows.push(row);
    }
    jmaki.log("rows count=" + _rows.length);
    return {rows : _rows};
}