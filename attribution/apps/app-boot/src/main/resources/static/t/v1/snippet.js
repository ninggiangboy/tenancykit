/**
 * Attribution's collection snippet. A Tenant adds it with the click parameter it configured, and it does three things:
 * remembers the click id from the landing URL, records page views, and reports a goal the Tenant names.
 *
 * No cookie is set until the visitor consents. Before consent it sends one landing page view carrying only the click id,
 * which is what lets a first click be counted without identifying the person. After consent it keeps the click id, a
 * random visitor id and a session id, and every page view carries them. The server enforces the same rule: with
 * `consent=false` it drops the identifiers before storing, so this is a convenience, not the guarantee.
 */
(function () {
  'use strict';
  var script = document.currentScript;
  if (!script) { return; }
  var base = script.getAttribute('data-collect') || '';
  var param = script.getAttribute('data-param') || 'aid';
  var endpoint = base + '/t/v1/events';
  var MAX_BATCH = 20;

  function read(name) {
    var match = new RegExp('[?&]' + name.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + '=([^&#]*)').exec(location.search);
    return match ? decodeURIComponent(match[1]) : null;
  }
  function write(name, value) {
    document.cookie = name + '=' + encodeURIComponent(value) + '; path=/; max-age=' + (60 * 60 * 24 * 30) + '; SameSite=Lax';
  }
  function readCookie(name) {
    var match = new RegExp('(^|; )' + name + '=([^;]*)').exec(document.cookie);
    return match ? decodeURIComponent(match[2]) : null;
  }
  function random() {
    var bytes = new Uint8Array(16);
    (window.crypto || window.msCrypto).getRandomValues(bytes);
    var out = '';
    for (var i = 0; i < bytes.length; i++) { out += ('0' + bytes[i].toString(16)).slice(-2); }
    return out;
  }

  var clickId = read(param) || readCookie('at_click');
  var visitorId = null;
  var sessionId = null;
  var consented = false;
  var queue = [];

  if (clickId) { write('at_click', clickId); }

  function newSession() {
    sessionId = random();
    document.cookie = 'at_session=' + sessionId + '; path=/; SameSite=Lax';
  }

  function send(events, beacon) {
    if (!events.length) { return; }
    var batch = events.slice(0, MAX_BATCH);
    var payload = JSON.stringify({ events: batch, consent: consented });
    if (beacon && navigator.sendBeacon) {
      navigator.sendBeacon(endpoint, new Blob([payload], { type: 'application/json' }));
    } else if (window.fetch) {
      fetch(endpoint, { method: 'POST', body: payload, headers: { 'Content-Type': 'application/json' }, keepalive: true })
        .catch(function () {});
    }
  }

  function pageEvent() {
    return {
      eventId: random(),
      clickId: clickId,
      visitorId: consented ? visitorId : null,
      sessionId: consented ? sessionId : null,
      path: location.pathname,
      occurredAt: new Date().toISOString()
    };
  }

  function flush() {
    if (!clickId) { return; }
    var events = queue.splice(0, queue.length);
    send(events, false);
  }

  function page() {
    if (!clickId) { return; }
    queue.push(pageEvent());
    if (queue.length >= MAX_BATCH) { flush(); }
  }

  function goal(key, options) {
    if (!clickId) { return; }
    options = options || {};
    var event = pageEvent();
    event.goal = key;
    event.value = options.value || null;
    event.currency = options.currency || null;
    queue.push(event);
    flush();
  }

  function consent(granted) {
    consented = granted === true;
    if (consented) {
      visitorId = readCookie('at_visitor');
      if (!visitorId) { visitorId = random(); write('at_visitor', visitorId); }
      if (!sessionId) { newSession(); }
    }
    page();
    flush();
  }

  window.attribution = {
    consent: consent,
    page: page,
    goal: goal
  };

  // The landing page view carries only the click id until the Tenant asks for consent.
  page();
  flush();

  window.addEventListener('pagehide', function () { flush(); });
})();
