"use strict";
/*
 * Pull-to-refresh arming probe (GeckoView edition).
 *
 * GeckoView 159 has no evaluateJavascript API, so the template runs
 * this content script in every top-level page instead. The script
 * keeps a native-messaging port open; when the user touches the page,
 * the app sends {type:"probe", id, x, y} (CSS pixels) and this script
 * answers {type:"probeResult", id, result:"ok"|"block"} using the
 * exact same elementFromPoint ancestor walk the WebView template
 * performs inline: "block" when any ancestor of the touched element
 * (or the document scroller) has scrollTop > 0, i.e. the gesture
 * belongs to an inner scroller, not to pull-to-refresh.
 */
let port = null;

function probe(x, y) {
  let el = document.elementFromPoint(x, y);
  while (el) {
    if (el.scrollTop > 0) return "block";
    el = el.parentElement;
  }
  const d = document.scrollingElement || document.documentElement;
  if (d && d.scrollTop > 0) return "block";
  return "ok";
}

function connect() {
  try {
    port = browser.runtime.connectNative("webgenPtrProbe");
  } catch (e) {
    port = null;
    return;
  }
  port.onMessage.addListener((msg) => {
    if (msg && msg.type === "probe") {
      let result = "block";
      try {
        result = probe(msg.x, msg.y);
      } catch (e) {
        result = "block";
      }
      try {
        port.postMessage({ type: "probeResult", id: msg.id, result: result });
      } catch (e) {
        /* port went away mid-gesture; the app simply never arms */
      }
    }
  });
  port.onDisconnect.addListener(() => {
    port = null;
  });
}

connect();
