(function () {
  'use strict';
  var P = 'orqix-extension/1';
  var host = window.parent;
  var handlers = [];
  var pending = {};
  var counter = 0;
  var current = null;
  var chains = [];
  var closed = false;
  function send(message) {
    if (closed) return;
    message.protocol = P;
    host.postMessage(message, '*');
  }
  // Leaving the approved document: tell the host first so it tears the frame down and sends nothing more, then drop
  // every reference this bridge holds to granted data and pending requests.
  function close() {
    if (closed) return;
    send({ type: 'unloading' });
    closed = true;
    current = null;
    chains = [];
    handlers.length = 0;
    pending = {};
  }
  function report(error) {
    var text = error && error.message ? error.message : String(error);
    send({ type: 'error', message: text.slice(0, 300) });
  }
  function notify(handler) {
    try { handler(current, chains); } catch (error) { report(error); }
  }
  window.addEventListener('message', function (event) {
    var message = event.data;
    if (closed || event.source !== host || !message || typeof message !== 'object' || message.protocol !== P) return;
    if (message.type === 'init' || message.type === 'data') {
      current = message.data && typeof message.data === 'object' ? message.data : {};
      chains = Array.isArray(message.chains) ? message.chains : chains;
      for (var i = 0; i < handlers.length; i++) notify(handlers[i]);
    } else if (message.type === 'result' && pending[message.requestId]) {
      var resolve = pending[message.requestId];
      delete pending[message.requestId];
      resolve(message.result);
    } else if (message.type === 'ping') {
      send({ type: 'pong', nonce: message.nonce });
    }
  });
  window.addEventListener('error', function (event) { report(event.message || 'Script error'); });
  window.addEventListener('unhandledrejection', function (event) { report(event.reason || 'Unhandled rejection'); });
  Object.defineProperty(window, 'orqix', {
    value: Object.freeze({
      onData: function (handler) {
        if (closed || typeof handler !== 'function') return;
        handlers.push(handler);
        if (current) notify(handler);
      },
      run: function (chainId) {
        return new Promise(function (resolve) {
          var requestId = 'r' + (++counter);
          pending[requestId] = resolve;
          send({ type: 'action', chainId: String(chainId), requestId: requestId });
        });
      },
      resize: function (height) { send({ type: 'resize', height: Number(height) }); }
    })
  });
  window.addEventListener('beforeunload', close);
  window.addEventListener('pagehide', close);
  window.addEventListener('load', function () { send({ type: 'ready' }); });
})();
