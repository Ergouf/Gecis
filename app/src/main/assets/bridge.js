// Android WebView bridge: GecisNative is injected by MainActivity.
(() => {
  document.body.dataset.platform = 'android';
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
  const parseJson = (raw) => typeof raw === 'string' ? JSON.parse(raw) : raw;
  window.GecisNative = {
    sendMessage(requestId, text) { return call(() => native.sendMessage(String(requestId), String(text))); },
    retryMessage(requestId, text) {
      if (typeof native.retryMessage === 'function') return call(() => native.retryMessage(String(requestId), String(text)));
      return call(() => native.sendMessage(String(requestId), String(text)));
    },
    getHistory() { return call(() => native.getHistory()); },
    getSetupStatus() { return call(() => parseJson(native.getSetupStatus())); },
    getRuntimeSettings() {
      return call(() => {
        const parsed = parseJson(native.getRuntimeSettings());
        if (parsed?.error) throw new Error(parsed.error);
        return parsed;
      });
    },
    getAvailableModels() {
      return call(() => {
        const parsed = parseJson(native.getAvailableModels());
        if (parsed?.error) throw new Error(parsed.error);
        return parsed;
      });
    },
    setRuntimeSettings(model, effort) {
      return call(() => {
        const parsed = parseJson(native.setRuntimeSettings(String(model || ''), String(effort || '')));
        if (parsed?.error) throw new Error(parsed.error);
        return parsed;
      });
    },
    createProject(name) { return call(() => native.createProject(String(name))); },
    newConversation(projectId) { return call(() => native.newConversation(Number(projectId))); },
    openConversation(conversationId) { return call(() => native.openConversation(Number(conversationId))); },
    moveConversation(conversationId, projectId) { return call(() => native.moveConversation(Number(conversationId), Number(projectId))); },
    deleteConversation(conversationId) { return call(() => native.deleteConversation(Number(conversationId))); },
    getConversationId() { return call(() => parseJson(native.getConversationId())); },
    exportConversation(format) { return call(() => native.exportConversation(String(format || 'md'))); },
    importFenbi() { return call(() => native.importFenbi()); },
    resumeConversation(agyId) { return call(() => parseJson(native.resumeConversation(String(agyId || '')))); },
    startLogin(requestId) { return call(() => native.startLogin(requestId == null ? '' : String(requestId))); },
    installRuntime() { return Promise.resolve('Android 使用内嵌引擎'); },
  };

  window.GecisBridgeReady = Promise.resolve();
})();