// Anpassungen der Web-App fuer die Android-App:
// Wecker laeuft nativ (auch bei geschlossener App), Einrichtung, automatisches Verbinden.
(function () {
  if (!window.Android) return;
  var A = window.Android;
  var el = function (id) { return document.getElementById(id); };
  var J = function (s, d) { try { return JSON.parse(s); } catch (e) { return d; } };

  // ---- Texte und Teile, die in der App keinen Sinn ergeben ----
  var intro = document.querySelector("body > p");
  if (intro) intro.textContent = "JBL-App schließen, Grip anschalten.";
  el("mtab").style.display = "none";
  var title = document.querySelector("h2");
  if (title) title.textContent = "Grip-Wecker";

  // ---- Einrichtung (Berechtigungen) ----
  var setup = document.createElement("div");
  setup.style.cssText = "border:2px solid #e60;border-radius:12px;padding:10px 12px;margin:8px 0;display:none";
  title.after(setup);
  function renderSetup() {
    var items = J(A.setup(), []);
    setup.style.display = items.length ? "block" : "none";
    setup.innerHTML = '<div style="font-weight:600;margin-bottom:4px">Einrichtung</div>';
    items.forEach(function (it) {
      var row = document.createElement("div");
      row.style.cssText = "display:flex;align-items:center;justify-content:space-between;gap:8px";
      var t = document.createElement("span"); t.textContent = it.text;
      var b = document.createElement("button"); b.textContent = it.btn;
      b.onclick = function () { A.fix(it.key); };
      row.appendChild(t); row.appendChild(b); setup.appendChild(row);
    });
  }

  // ---- Grip: gespeicherten automatisch verbinden, anderen waehlen ----
  var other = document.createElement("button");
  other.textContent = "Anderen Grip";
  other.onclick = function () { A.forgetDevice(); window.__gb.useSaved = false; el("conn").click(); };
  el("st").after(other);
  function autoConnect() {
    if (A.savedDevice()) { window.__gb.useSaved = true; el("conn").click(); }
  }

  // ---- Wecker nativ ----
  var info = el("atime").parentElement.previousElementSibling;
  if (info) info.textContent = "Läuft auf diesem Handy, auch wenn die App zu ist. Licht ab Vorlauf, Ton vom Handy. Weckbildschirm erscheint auch auf dem Sperrbildschirm.";

  var rise = el("arise");
  rise.innerHTML = [5, 10, 15, 20, 30, 45, 60].map(function (m) {
    return '<option value="' + m + '">' + m + " min</option>";
  }).join("");

  var snd = el("asound");
  var lastSound = "phone";
  function soundOptions(customName) {
    snd.innerHTML =
      '<option value="phone">Handy-Weckton</option>' +
      (customName ? '<option value="custom"></option>' : "") +
      '<option value="pick">Anderen Ton wählen …</option>' +
      '<option value="none">Kein Ton (nur Licht)</option>';
    if (customName) snd.querySelector('[value="custom"]').textContent = customName;
  }
  snd.onchange = function () {
    if (snd.value === "pick") { snd.value = lastSound; A.pickSound(); }
    else lastSound = snd.value;
  };

  el("aready").style.display = "none";
  var line = document.createElement("div");
  line.style.cssText = "font-size:14px;color:#555;margin:6px 0";
  el("aready").after(line);
  var ringBtnBox = el("aring");

  function load() {
    var c = J(A.getAlarm(), null);
    if (!c) return;
    soundOptions(c.soundName);
    acfg = { on: c.on, time: c.time, days: c.days, rise: c.rise, sound: c.sound };
    armed = null; // Web-Wecker aus, der native uebernimmt
    cfgToUi();
    lastSound = c.sound;
  }

  window.__gw = {
    soundPicked: function (name) {
      if (name) { soundOptions(name); snd.value = "custom"; lastSound = "custom"; }
      else { snd.value = "phone"; lastSound = "phone"; }
    },
    refresh: function () { renderSetup(); upd(); }
  };

  var oSave = el("asave").onclick, oTest = el("atest").onclick, oStop = el("astop").onclick, oSnooze = el("asnooze").onclick;
  var remote = function () { return typeof relayRole !== "undefined" && relayRole === "remote"; };
  el("asave").onclick = function () {
    if (remote()) return oSave();
    var c = uiToCfg();
    acfg = c; armed = null;
    log(A.saveAlarm(JSON.stringify(c)));
    upd();
  };
  el("atest").onclick = function () { if (remote()) return oTest(); A.test(); log("Test: Sonnenaufgang 1 min, dann Ton"); };
  el("astop").onclick = function () { if (remote()) return oStop(); A.stopAlarm(); };
  el("asnooze").onclick = function () { if (remote()) return oSnooze(); A.snooze(); };

  function upd() {
    if (remote()) return;
    var s = J(A.status(), null);
    if (!s) return;
    armed = null;
    el("anext").textContent = s.text;
    line.textContent = s.line;
    ringBtnBox.style.display = s.phase === "ringing" ? "block" : "none";
  }

  load();
  renderSetup();
  upd();
  setInterval(upd, 2000);
  setTimeout(autoConnect, 300);
})();
