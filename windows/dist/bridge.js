// GecisNative bridge: Tauri invoke shim preserving the Android WebView contract.
(() => {
  const api = window.__TAURI__;
  if (!api?.core?.invoke) {
    window.GecisNative = {
      sendMessage() { throw new Error('Tauri bridge 未就绪'); },
      getHistory() { throw new Error('Tauri bridge 未就绪'); },
      createProject() { throw new Error('Tauri bridge 未就绪'); },
      newConversation() { throw new Error('Tauri bridge 未就绪'); },
      openConversation() { throw new Error('Tauri bridge 未就绪'); },
    };
    return;
  }

  const { invoke } = api.core;
  const { listen } = api.event;

  window.GecisNative = {
    sendMessage(requestId, text) {
      return invoke('send_message', { requestId, text });
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
    runtimeStatus() {
      return invoke('runtime_status');
    },
    startLogin() {
      return invoke('start_login');
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
    getConversationId() {
      return invoke('get_conversation_id');
    },
    exportConversation(format) {
      return invoke('export_conversation', { format });
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
