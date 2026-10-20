/*
 * The TV's side of the web home screen: its apps, opening one, its inputs, the Back key.
 * LG webOS through the TV's own service bus (Luna, the same calls LG's launcher makes). In a desktop browser, a stand-in
 * with sample apps, so the screen can be previewed and tested on a computer.
 * Plain ES5: older LG TVs (webOS 3 and 4) run a Chromium from 2015-2017.
 */
var TV = (function () {
  'use strict';

  var webos = typeof window.PalmServiceBridge !== 'undefined';
  var calls = [];   // a bridge is collected if nothing holds it before its reply arrives

  /** One Luna call; done(result) with null on failure. */
  function luna(uri, params, done) {
    if (!webos) {
      if (done) setTimeout(function () { done(null); }, 0);
      return;
    }
    var bridge = new window.PalmServiceBridge();
    calls.push(bridge);
    var finished = false;
    bridge.onservicecallback = function (message) {
      if (finished) return;
      finished = true;
      var i = calls.indexOf(bridge);
      if (i >= 0) calls.splice(i, 1);
      var result = null;
      try { result = JSON.parse(message); } catch (e) {}
      if (done) done(result && result.returnValue !== false ? result : null);
    };
    bridge.call(uri, JSON.stringify(params || {}));
  }

  // Inputs: the antenna and the four HDMI ports, with the apps LG opens for them.
  var INPUTS = [
    {id: 'com.webos.app.livetv', key: 'live', label: null, symbol: 'live_tv'},
    {id: 'com.webos.app.hdmi1', key: 'hdmi1', label: 'HDMI 1', symbol: 'hdmi'},
    {id: 'com.webos.app.hdmi2', key: 'hdmi2', label: 'HDMI 2', symbol: 'hdmi'},
    {id: 'com.webos.app.hdmi3', key: 'hdmi3', label: 'HDMI 3', symbol: 'hdmi'},
    {id: 'com.webos.app.hdmi4', key: 'hdmi4', label: 'HDMI 4', symbol: 'hdmi'}
  ];

  // Not shown among the apps: the inputs above, LG's own home and launchers, and this app.
  var HIDDEN = /^(com\.webos\.app\.(livetv|hdmi\d|home|homeconnect|discovery|launcher)|com\.webos\.app\.inputpicker)$/;

  var SAMPLE = [
    ['netflix', 'Netflix', '#E50914'], ['youtube.leanback.v4', 'YouTube', '#FF0033'], ['amazon', 'Prime Video', '#1A98FF'],
    ['com.disney.disneyplus-prod', 'Disney+', '#1838C8'], ['com.hbo.hbomax', 'Max', '#6C3BE0'],
    ['com.apple.appletv', 'Apple TV', '#B8BCC6'], ['globoplaywebos', 'Globoplay', '#FF5A1F'], ['spotify-beehive', 'Spotify', '#1DB954'],
    ['cdp-30', 'Plex', '#E5A00D'], ['com.crunchyroll.webapp', 'Crunchyroll', '#F47521'], ['com.webos.app.browser', 'Web Browser', '#3A7BD5'],
    ['com.palm.app.settings', 'Settings', '#8E8E93']
  ];

  var self = typeof window.PalmSystem !== 'undefined' && window.PalmSystem.identifier
      ? window.PalmSystem.identifier.split(' ')[0] : 'io.freemytv';

  return {
    name: webos ? 'webos' : 'browser',

    /** Installed apps, as LG's launcher lists them: [{id, title, icon, color}]. */
    apps: function (done) {
      if (!webos) {
        done(SAMPLE.map(function (s) { return {id: s[0], title: s[1], icon: '', color: s[2]}; }));
        return;
      }
      luna('luna://com.webos.applicationManager/listLaunchPoints', {}, function (res) {
        var out = [];
        var points = res && res.launchPoints ? res.launchPoints : [];
        for (var i = 0; i < points.length; i++) {
          var p = points[i];
          if (!p.id || p.id === self || HIDDEN.test(p.id)) continue;
          out.push({id: p.id, title: p.title || p.id, icon: p.largeIcon || p.icon || '', color: p.bgColor || p.iconColor || ''});
        }
        done(out);
      });
    },

    /** Opens an app (or an input); done(ok). */
    launch: function (id, params, done) {
      if (!webos) {
        if (done) setTimeout(function () { done(true); }, 0);
        return;
      }
      luna('luna://com.webos.applicationManager/launch', params ? {id: id, params: params} : {id: id}, function (res) {
        if (done) done(!!res);
      });
    },

    /** The antenna and HDMI inputs; `connected` when the TV says a device is on (when it tells). */
    inputs: function (done) {
      var list = INPUTS.map(function (x) { return {id: x.id, key: x.key, label: x.label, symbol: x.symbol, connected: true}; });
      if (!webos) {
        list[2].connected = false;
        list[3].connected = false;
        list[4].connected = false;
        done(list);
        return;
      }
      luna('luna://com.webos.service.eim/getAllInputStatus', {}, function (res) {
        var devices = res && res.devices ? res.devices : null;
        if (devices) {
          for (var i = 1; i < list.length; i++) {
            for (var j = 0; j < devices.length; j++) {
              var d = devices[j];
              if (d.appId !== list[i].id) continue;
              list[i].connected = d.connected !== false;
              // A name the person gave the input on the TV ("PS5"), if any.
              if (d.label && !/^hdmi\s*\d$/i.test(d.label)) list[i].label = d.label;
            }
          }
        }
        done(list);
      });
    },

    /** The TV's own settings. */
    settings: function () { this.launch('com.palm.app.settings'); },

    /** Back on the first screen: back to wherever the person came from. */
    back: function () {
      if (window.PalmSystem && window.PalmSystem.platformBack) window.PalmSystem.platformBack();
    },

    /** Key codes: the arrows, OK and LG's Back. */
    keys: {left: 37, up: 38, right: 39, down: 40, ok: 13, back: 461}
  };
})();
