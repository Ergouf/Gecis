// GecisNative bridge: Tauri invoke shim preserving the Android WebView contract.
(() => {
  document.body.dataset.platform = 'windows';
  const api = window.__TAURI__;
  if (!api?.core?.invoke) {
    window.GecisNative = {
      sendMessage() { throw new Error('Tauri bridge 未就绪'); },
      retryMessage() { throw new Error('Tauri bridge 未就绪'); },
      getHistory() { throw new Error('Tauri bridge 未就绪'); },
      createProject() { throw new Error('Tauri bridge 未就绪'); },
      newConversation() { throw new Error('Tauri bridge 未就绪'); },
      openConversation() { throw new Error('Tauri bridge 未就绪'); },
      moveConversation() { throw new Error('Tauri bridge 未就绪'); },
      deleteConversation() { throw new Error('Tauri bridge 未就绪'); },
    };
    return;
  }

  const { invoke } = api.core;
  const { listen } = api.event;

  window.GecisNative = {
    sendMessage(requestId, text) {
      return invoke('send_message', { requestId, text });
    },
    retryMessage(requestId, text) {
      return invoke('retry_message', { requestId, text });
    },
    getHistory() {
      return invoke('get_history');
    },
    createProject(name) {
      return invoke('create_project', { name });
    },
    newConversation(projectId) {
      return invoke('new_conversation', { projectId });
    },
    openConversation(conversationId) {
      return invoke('open_conversation', { conversationId });
    },
    moveConversation(conversationId, projectId) {
      return invoke('move_conversation', { conversationId, projectId });
    },
    deleteConversation(conversationId) {
      return invoke('delete_conversation', { conversationId });
    },
    runtimeStatus() {
      return invoke('runtime_status');
    },
    startLogin(requestId) {
      return invoke('start_login', { requestId: requestId || null });
    },
    installRuntime() {
      return invoke('install_runtime');
    },
    importFenbi() {
      return invoke('import_fenbi');
    },
    resumeConversation(agyId) {
      return invoke('resume_conversation', { agyId });
    },
    getSetupStatus() {
      return invoke('get_setup_status');
    },
    getConversationId() {
      return invoke('get_conversation_id');
    },
    exportConversation(format) {
      return invoke('export_conversation', { format });
    },
    getRuntimeSettings() {
      return invoke('get_runtime_settings');
    },
    getAvailableModels() {
      return invoke('get_available_models').then((parsed) => {
        if (parsed?.error) throw new Error(parsed.error);
        return parsed;
      });
    },
    setRuntimeSettings(model, effort) {
      return invoke('set_runtime_settings', { model: model || '', effort: effort || '' });
    },
  };

  window.GecisBridgeReady = (async () => {
    await listen('gecis://status', (event) => {
      window.GecisChat?.onStatus(event.payload);
    });
    await listen('gecis://native-event', (event) => {
      window.GecisChat?.onNativeEvent(event.payload);
    });
    await listen('gecis://history', (event) => {
      window.GecisChat?.onHistory(event.payload);
    });
    await listen('gecis://resume-turn', (event) => {
      window.GecisChat?.onResumeTurn(event.payload);
    });
    await invoke('bridge_ready');
  })();
})();
