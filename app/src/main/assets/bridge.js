// Android WebView bridge: GecisNative is injected by MainActivity.
(() => {
  if (!window.GecisNative?.sendMessage) {
    window.GecisBridgeReady = Promise.reject(new Error('原生桥未连接'));
    return;
  }
  const native = window.GecisNative;
  window.GecisNative = {
    sendMessage(requestId, text) {
      try { native.sendMessage(requestId, text); return Promise.resolve(); }
      catch (e) { return Promise.reject(e); }
    },
    getHistory() {
      try { return Promise.resolve(native.getHistory()); }
      catch (e) { return Promise.reject(e); }
    },
    createProject(name) {
      try { return Promise.resolve(native.createProject(name)); }
      catch (e) { return Promise.reject(e); }
    },
    newConversation(projectId) {
      try { return Promise.resolve(native.newConversation(projectId)); }
      catch (e) { return Promise.reject(e); }
    },
    openConversation(conversationId) {
      try { return Promise.resolve(native.openConversation(conversationId)); }
      catch (e) { return Promise.reject(e); }
    },
    getConversationId() {
      try {
        const raw = native.getConversationId();
        return Promise.resolve(typeof raw === 'string' ? JSON.parse(raw) : raw);
      } catch (e) { return Promise.reject(e); }
    },
    exportConversation(format) {
      try { return Promise.resolve(native.exportConversation(String(format || 'md'))); }
      catch (e) { return Promise.reject(e); }
    },
    importFenbi() { return Promise.resolve('skipped'); },
    startLogin() { return Promise.resolve('请在系统浏览器完成 Google 登录'); },
    installRuntime() { return Promise.resolve('Android 使用内嵌引擎'); },
  };
  window.GecisBridgeReady = Promise.resolve();
})();
