// Web-Bluetooth-Ersatz fuer die Android-WebView.
// Die Web-App bleibt unveraendert; navigator.bluetooth leitet hier an die native App weiter.
(function () {
  if (!window.Android) return;
  var A = window.Android;
  var cbs = { pick: null, connect: null };
  var dev = null;

  function makeDev(id, name) {
    var d = new EventTarget();
    d.id = id;
    d.name = name;
    var chr = {
      writeValueWithoutResponse: function (buf) {
        var b = buf instanceof Uint8Array ? buf : new Uint8Array(buf.buffer || buf);
        if (!d.gatt.connected) return Promise.reject(new Error("nicht verbunden"));
        if (b.length >= 13 && b[1] === 0xdd) A.write(b[8] | (b[9] << 8), b[12]);
        return Promise.resolve();
      },
      writeValue: function (buf) { return this.writeValueWithoutResponse(buf); },
      startNotifications: function () { return Promise.resolve(chr); },
      addEventListener: function () {},
      removeEventListener: function () {}
    };
    var svc = { getCharacteristic: function () { return Promise.resolve(chr); } };
    d.gatt = {
      connected: false,
      device: d,
      connect: function () {
        return new Promise(function (res, rej) {
          cbs.connect = { res: function () { res(d.gatt); }, rej: rej };
          A.connect(id);
        });
      },
      disconnect: function () { A.disconnect(); d.gatt.connected = false; },
      getPrimaryService: function () {
        return d.gatt.connected ? Promise.resolve(svc) : Promise.reject(new Error("nicht verbunden"));
      }
    };
    return d;
  }

  function saved() {
    try { return JSON.parse(A.savedDevice() || "null"); } catch (e) { return null; }
  }

  window.__gb = {
    useSaved: false,
    picked: function (id, name) {
      dev = makeDev(id, name);
      var p = cbs.pick; cbs.pick = null;
      if (p) p.res(dev);
    },
    pickFailed: function (msg) {
      var p = cbs.pick; cbs.pick = null;
      if (p) p.rej(new Error(msg));
    },
    state: function (s) {
      if (!dev) return;
      var c = cbs.connect; cbs.connect = null;
      if (s === "connected") {
        dev.gatt.connected = true;
        if (c) c.res();
      } else {
        var was = dev.gatt.connected;
        dev.gatt.connected = false;
        if (c) c.rej(new Error(s));
        if (was) dev.dispatchEvent(new Event("gattserverdisconnected"));
      }
    }
  };

  var bt = {
    requestDevice: function () {
      return new Promise(function (res, rej) {
        var s = saved();
        if (window.__gb.useSaved && s) {
          window.__gb.useSaved = false;
          if (!dev || dev.id !== s.id) dev = makeDev(s.id, s.name);
          res(dev);
          return;
        }
        window.__gb.useSaved = false;
        cbs.pick = { res: res, rej: rej };
        A.pickDevice();
      });
    },
    getDevices: function () {
      var s = saved();
      if (!s) return Promise.resolve([]);
      if (!dev || dev.id !== s.id) dev = makeDev(s.id, s.name);
      return Promise.resolve([dev]);
    }
  };
  Object.defineProperty(navigator, "bluetooth", { value: bt, configurable: true });
})();
