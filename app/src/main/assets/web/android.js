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
  try { design(); } catch (e) { document.body.classList.add("ready"); log("Design-Fehler: " + e); }

  // ---- App-Design: Karten statt langer Liste (Stil in app.css) ----
  function design() {
    var B = document.body;
    var link = document.createElement("link");
    link.rel = "stylesheet"; link.href = "app.css";
    var show = function () { B.classList.add("ready"); };
    link.onload = show; link.onerror = show;
    document.head.appendChild(link);
    setTimeout(show, 1000);

    function mk(tag, cls, html) {
      var e = document.createElement(tag);
      if (cls) e.className = cls;
      if (html != null) e.innerHTML = html;
      return e;
    }
    function card(title, right) {
      var c = mk("div", "card"), h = mk("div", "row");
      h.appendChild(mk("span", "ct", title));
      if (right) h.appendChild(right);
      c.appendChild(h);
      return c;
    }
    function fold(title, cls) {
      var d = mk("details", cls);
      d.appendChild(mk("summary", null, title));
      return d;
    }

    // Referenzen sammeln, bevor etwas verschoben wird
    var st = el("st"), conn = el("conn");
    var onoff = el("on").parentElement;
    var aon = el("aon"), aonLabel = aon.parentElement, timeBox = el("atime").parentElement;
    var riseDiv = el("arise").parentElement, gradText = riseDiv.nextElementSibling, sndDiv = el("asound").parentElement;
    var modes = document.querySelector("button.m").parentElement;
    var pShow = modes.previousElementSibling, pFarb = el("mst").nextElementSibling;
    var spL = el("sp").parentElement, pHell = spL.nextElementSibling, loL = el("lo").parentElement, hiL = el("hi").parentElement;
    var rcodeL = el("rcode").parentElement, pRelay = rcodeL.previousElementSibling;

    // Kopf
    var hdr = mk("div", "hdr"), top = mk("div", "row");
    top.appendChild(mk("div", "brand", '<i class="logo"></i><span>Grip-Wecker</span>'));
    st.className = "chip";
    top.appendChild(st);
    var r2 = mk("div", "row2");
    conn.classList.add("pri");
    r2.appendChild(conn); r2.appendChild(other);
    hdr.appendChild(top); hdr.appendChild(r2);
    var chipState = function () { st.classList.toggle("ok", /^verbunden/.test(st.textContent)); };
    new MutationObserver(chipState).observe(st, { childList: true, characterData: true, subtree: true });
    chipState();
    setup.style.border = "";
    setup.className = "card setup";

    // Licht
    onoff.className = "pills";
    el("on").textContent = "An"; el("off").textContent = "Aus";
    var lc = card("Licht", onoff);
    var dots = mk("div", "dots");
    document.querySelectorAll("button.c").forEach(function (b) { dots.appendChild(b); });
    dots.addEventListener("click", function (e) {
      if (!e.target.classList.contains("c")) return;
      dots.querySelectorAll(".c").forEach(function (b) { b.classList.toggle("sel", b === e.target); });
    });
    lc.appendChild(dots);
    var brow = mk("div", "row lb"), val = mk("span", "val");
    brow.appendChild(mk("span", null, "Helligkeit"));
    val.appendChild(el("pv")); val.appendChild(document.createTextNode(" %"));
    brow.appendChild(val);
    brow.style.marginTop = "10px";
    lc.appendChild(brow); lc.appendChild(el("br"));

    // Wecker
    var wc = card("Wecker", aon);
    wc.appendChild(el("atime"));
    el("anext").className = "lb";
    wc.appendChild(el("anext"));
    var ad = el("adays");
    Array.from(ad.childNodes).forEach(function (n) { if (n.nodeType === 3 || n.nodeName === "BR") n.remove(); });
    ad.querySelectorAll("label").forEach(function (l) { l.style.cssText = ""; });
    wc.appendChild(mk("div", "lb sp", "Tage (keiner = einmalig)"));
    wc.appendChild(ad);
    wc.appendChild(mk("div", "grbar"));
    wc.appendChild(mk("div", "row lb small", "<span>Rot 3 %</span><span>Orange</span><span>Blau 100 %</span>"));
    function srow(label, sel) { var r = mk("div", "row set"); r.appendChild(mk("span", "lb", label)); r.appendChild(sel); return r; }
    wc.appendChild(srow("Vorlauf", el("arise")));
    wc.appendChild(srow("Ton", el("asound")));
    line.style.cssText = "";
    line.className = "lb set";
    wc.appendChild(line);
    var ab = mk("div", "row btns");
    el("asave").classList.add("pri");
    ab.appendChild(el("asave")); ab.appendChild(el("atest"));
    wc.appendChild(ab);
    wc.appendChild(el("aring"));
    if (info) { info.removeAttribute("style"); info.className = "lb note"; wc.appendChild(info); }

    // Lichtshow
    var sc = card("Lichtshow");
    sc.appendChild(mk("div", "lb sp", "Grip-Themes"));
    el("themes").className = "pills";
    el("themes").addEventListener("click", function (e) {
      if (e.target.tagName !== "BUTTON") return;
      el("themes").querySelectorAll("button").forEach(function (b) { b.classList.toggle("sel", b === e.target); });
    });
    el("themes").querySelectorAll("button").forEach(function (b) {
      b.textContent = b.textContent.charAt(0) + b.textContent.slice(1).toLowerCase();
    });
    sc.appendChild(el("themes"));
    sc.appendChild(mk("div", "lb sp", "Eigene Show"));
    modes.className = "pills";
    el("mstop").textContent = "Stopp";
    sc.appendChild(modes);
    el("mst").removeAttribute("style");
    el("mst").className = "lb";
    sc.appendChild(el("mst"));
    sc.appendChild(el("beat"));
    var more = fold("Mehr Einstellungen", "more");
    [pShow, el("cset"), pFarb, spL, pHell, loL, hiL].forEach(function (n) {
      if (!n) return;
      if (n.tagName === "P") n.removeAttribute("style");
      more.appendChild(n);
    });
    sc.appendChild(more);

    // Fernbedienung und Protokoll eingeklappt
    var rc = fold("Fernbedienung (Relais)", "card");
    if (pRelay) { pRelay.removeAttribute("style"); rc.appendChild(pRelay); }
    rc.appendChild(rcodeL);
    var rb = mk("div", "row2");
    rb.appendChild(el("rhost")); rb.appendChild(el("rremote"));
    rc.appendChild(rb);
    el("rst").removeAttribute("style");
    rc.appendChild(el("rst"));
    var lg = fold("Protokoll", "card");
    lg.appendChild(el("log"));

    // Alte Reste entfernen, neue Ordnung einsetzen
    [aonLabel, timeBox, riseDiv, gradText, sndDiv].forEach(function (n) { if (n && n.parentElement) n.remove(); });
    Array.from(B.children).forEach(function (c) { if (c.tagName !== "SCRIPT" && c.tagName !== "STYLE" && c.tagName !== "LINK") c.remove(); });
    B.prepend(hdr, setup, lc, wc, sc, rc, lg);

    // Regler: gefuellter Teil im Farbverlauf
    var fill = function () {
      document.querySelectorAll("input[type=range]").forEach(function (r) {
        var p = (r.value - r.min) / ((r.max - r.min) || 1) * 100;
        r.style.setProperty("--p", p + "%");
      });
    };
    document.addEventListener("input", fill);
    setInterval(fill, 1000);
    fill();
  }
})();
