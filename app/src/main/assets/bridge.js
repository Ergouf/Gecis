// Android WebView bridge: GecisNative is injected by MainActivity.
(() => {
  if (!window.__GECIS_NATIVE__ && !window.GecisNative?.sendMessage) {
    window.GecisBridgeReady = Promise.reject(new Error('原生桥未连接'));
    return;
  }
  const native = window.__GECIS_NATIVE__ || window.GecisNative;
  const call = (fn) => {
    try {
      const v = fn();
      return Promise.resolve(v);
    } catch (e) {
      return Promise.reject(e instanceof Error ? e : new Error(String(e)));
    }
  };
  window.GecisNative = {
    sendMessage(requestId, text) {
      return call(() => native.sendMessage(String(requestId), String(text)));
    },
    getHistory() {
      return call(() => native.getHistory());
    },
    createProject(name) {
      return call(() => native.createProject(String(name)));
    },
    newConversation(projectId) {
      return call(() => native.newConversation(Number(projectId)));
    },
    openConversation(conversationId) {
      return call(() => native.openConversation(Number(conversationId)));
    },
    getConversationId() {
      return call(() => {
        const raw = native.getConversationId();
        return typeof raw === 'string' ? JSON.parse(raw) : raw;
      });
    },
    exportConversation(format) {
      return call(() => native.exportConversation(String(format || 'md')));
    },
    // Opens system file picker and imports fenbi.db. Returns "ok:<name>" | "cancelled" | throws.
    importFenbi() {
      return call(() => native.importFenbi());
    },
    resumeConversation(agyId) {
      return call(() => {
        const raw = native.resumeConversation(String(agyId || ''));
        return typeof raw === 'string' ? JSON.parse(raw) : raw;
      });
    },
    startLogin() {
      return call(() => native.startLogin());
    },
    installRuntime() {
      return Promise.resolve('Android 使用内嵌引擎');
    },
  };
  window.GecisBridgeReady = Promise.resolve();
})();
