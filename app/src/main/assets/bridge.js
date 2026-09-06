/*
 * bridge.js — runs INSIDE the authenticated photos.google.com page.
 *
 * Why it works this way:
 *   Every Google request is issued from the page's own origin with
 *   `credentials: 'include'`. The session cookies therefore never leave the
 *   WebView's cookie jar — the Kotlin side of this app never sees, stores, or
 *   is even able to read a Google credential. It can only ask for named
 *   operations and receive parsed results back.
 *
 * `gpcBridge` is injected by WebViewCompat.addWebMessageListener and is scoped
 * to https://photos.google.com only. If this script is ever evaluated on some
 * other origin, `gpcBridge` is simply undefined and nothing can be exfiltrated.
 */
(function () {
  'use strict';

  if (window.__gpc) return; // already installed

  var MAX_RETRIES = 4;
  var RETRY_BASE_MS = 800;

  function tokens() {
    var G = window.WIZ_global_data || {};
    return {
      at: G.SNlM0e,        // XSRF token
      fsid: G.FdrFJe,      // session id
      bl: G.cfb2h,         // backend build label
      path: G.eptZe || '/',// account-scoped path prefix, e.g. "/u/1/"
      rapt: G.Dbw5Ud,      // present only inside the Locked Folder
      account: G.oPEP7c    // signed-in account index/id
    };
  }

  function reply(msg) {
    try {
      gpcBridge.postMessage(JSON.stringify(msg));
    } catch (e) {
      /* bridge absent => wrong origin => intentionally do nothing */
    }
  }

  function sleep(ms) {
    return new Promise(function (r) { setTimeout(r, ms); });
  }

  /* Is there a usable signed-in session on this page? */
  function probe() {
    var t = tokens();
    return {
      ready: !!(t.at && t.fsid && t.bl),
      account: typeof t.account === 'string' ? t.account : null,
      path: t.path
    };
  }

  async function rpc(rpcid, data) {
    var t = tokens();
    if (!t.at || !t.fsid || !t.bl) {
      throw new Error('NO_SESSION');
    }

    var wrapped = [[[rpcid, JSON.stringify(data), null, 'generic']]];
    var body = 'f.req=' + encodeURIComponent(JSON.stringify(wrapped)) +
               '&at=' + encodeURIComponent(t.at) + '&';

    var params = new URLSearchParams({
      'rpcids': rpcid,
      'source-path': location.pathname,
      'f.sid': t.fsid,
      'bl': t.bl,
      'pageId': 'none',
      'rt': 'c'
    });
    if (typeof t.rapt === 'string') params.set('rapt', t.rapt);

    var url = 'https://photos.google.com' + t.path + 'data/batchexecute?' + params.toString();

    var lastErr = null;
    for (var attempt = 1; attempt <= MAX_RETRIES; attempt++) {
      try {
        var res = await fetch(url, {
          method: 'POST',
          credentials: 'include',
          headers: { 'content-type': 'application/x-www-form-urlencoded;charset=UTF-8' },
          body: body
        });

        // 401/403 means the session died; retrying will not help.
        if (res.status === 401 || res.status === 403) throw new Error('NO_SESSION');
        // 429 is Google rate-limiting us. Back off hard rather than hammering.
        if (res.status === 429) throw new Error('RATE_LIMITED');
        if (!res.ok) throw new Error('HTTP_' + res.status);

        var text = await res.text();
        if (!text) throw new Error('EMPTY_BODY');

        var line = null;
        var lines = text.split('\n');
        for (var i = 0; i < lines.length; i++) {
          if (lines[i].indexOf('wrb.fr') !== -1) { line = lines[i]; break; }
        }
        if (!line) throw new Error('NO_ENVELOPE');

        var parsed = JSON.parse(line);
        if (!parsed || !parsed[0] || parsed[0][2] == null) {
          // A null payload on a mutation call is a valid "nothing to return".
          return null;
        }
        return JSON.parse(parsed[0][2]);
      } catch (e) {
        lastErr = e;
        var m = e && e.message ? e.message : String(e);
        if (m === 'NO_SESSION') throw e;          // fail fast, force re-login
        if (attempt < MAX_RETRIES) {
          // Exponential-ish backoff; extra patience when explicitly rate-limited.
          await sleep(RETRY_BASE_MS * attempt * (m === 'RATE_LIMITED' ? 4 : 1));
        }
      }
    }
    throw lastErr || new Error('RPC_FAILED');
  }

  window.__gpc = {
    probe: probe,

    /* Entry point called from Kotlin via evaluateJavascript. */
    call: function (id, rpcid, argsJson) {
      var data;
      try {
        data = JSON.parse(argsJson);
      } catch (e) {
        reply({ id: id, ok: false, error: 'BAD_ARGS' });
        return;
      }
      rpc(rpcid, data).then(function (result) {
        reply({ id: id, ok: true, data: JSON.stringify(result === undefined ? null : result) });
      }).catch(function (e) {
        reply({ id: id, ok: false, error: (e && e.message) ? e.message : 'RPC_FAILED' });
      });
    },

    /* Session probe, answered over the same channel. */
    checkSession: function (id) {
      var p;
      try {
        p = probe();
      } catch (e) {
        p = { ready: false, account: null, path: '/' };
      }
      reply({ id: id, ok: true, data: JSON.stringify(p) });
    }
  };

  reply({ id: '__installed', ok: true, data: JSON.stringify(probe()) });
})();
