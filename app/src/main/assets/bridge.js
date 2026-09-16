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

  const installModelPicker = () => {
    const composerWrap = document.querySelector('.composer-wrap');
    const composer = document.getElementById('form');
    if (!composerWrap || !composer) return;

    composer.querySelector('.runtime-controls')?.remove();

    const style = document.createElement('style');
    style.textContent = `
      body[data-platform="android"] .composer { flex-wrap: nowrap; align-items: flex-end; gap: 10px; padding: 12px 12px 12px 16px; }
      body[data-platform="android"] .composer textarea { flex: 1 1 auto; width: auto; min-width: 0; min-height: 44px; padding: 8px 0 5px; }
      body[data-platform="android"] .model-toolbar { width: min(var(--content-width),100%); margin: 0 auto 7px; padding: 0 4px; display: flex; align-items: center; gap: 6px; overflow: hidden; }
      body[data-platform="android"] .model-picker { height: 34px; min-width: 0; max-width: 62vw; padding: 0 28px 0 10px; border: 0; border-radius: 9px; outline: 0; background: rgba(244,244,246,.96); color: var(--ink-soft); font-size: 12px; text-overflow: ellipsis; }
      body[data-platform="android"] #providerModelSelect { flex: 0 1 auto; max-width: 58vw; }
      body[data-platform="android"] #providerVariantSelect { flex: 0 0 auto; max-width: 30vw; }
      body[data-platform="android"] .model-refresh { width: 34px; height: 34px; min-width: 34px; min-height: 34px; padding: 0; border: 0; border-radius: 9px; display: grid; place-items: center; background: transparent; color: var(--muted); font-size: 17px; }
      body[data-platform="android"] .model-refresh:active { background: var(--hover); }
      body[data-platform="android"] .model-refresh[disabled], body[data-platform="android"] .model-picker[disabled] { opacity: .52; }
      body[data-platform="android"] .messages { padding-bottom: 196px; }
      @media (max-width: 520px) {
        body[data-platform="android"] .model-toolbar { padding: 0 2px; }
        body[data-platform="android"] #providerModelSelect { max-width: 60vw; }
        body[data-platform="android"] #providerVariantSelect { max-width: 27vw; }
      }
    `;
    document.head.appendChild(style);

    const toolbar = document.createElement('div');
    toolbar.className = 'model-toolbar';
    toolbar.setAttribute('aria-label', 'AI 模型设置');

    const modelSelect = document.createElement('select');
    modelSelect.id = 'providerModelSelect';
    modelSelect.className = 'model-picker';
    modelSelect.setAttribute('aria-label', '模型');
    modelSelect.innerHTML = '<option value="">读取模型…</option>';

    const variantSelect = document.createElement('select');
    variantSelect.id = 'providerVariantSelect';
    variantSelect.className = 'model-picker';
    variantSelect.setAttribute('aria-label', '思考等级');
    variantSelect.hidden = true;

    const refresh = document.createElement('button');
    refresh.id = 'refreshProviderModels';
    refresh.className = 'model-refresh';
    refresh.type = 'button';
    refresh.title = '刷新上游模型';
    refresh.setAttribute('aria-label', '刷新上游模型');
    refresh.textContent = '↻';

    toolbar.append(modelSelect, variantSelect, refresh);
    composerWrap.insertBefore(toolbar, composer);

    let families = [];
    let currentSlug = null;
    let applying = false;
    const report = (text, state = 'idle') => window.GecisChat?.onStatus?.({ text, state });
    const familyForSlug = (slug) => families.find((f) => (f.variants || []).some((v) => v.slug === slug));
    const preferredVariant = (family) => {
      const variants = family?.variants || [];
      return variants.find((v) => v.effort === 'medium') || variants[0] || null;
    };

    const renderVariants = (family, selectedSlug) => {
      const variants = family?.variants || [];
      variantSelect.replaceChildren();
      if (variants.length <= 1) {
        variantSelect.hidden = true;
        return variants[0] || null;
      }
      for (const variant of variants) {
        const option = document.createElement('option');
        option.value = variant.slug;
        option.textContent = variant.label || variant.effort || '默认';
        variantSelect.appendChild(option);
      }
      const selected = variants.find((v) => v.slug === selectedSlug) || preferredVariant(family);
      if (selected) variantSelect.value = selected.slug;
      variantSelect.hidden = false;
      return selected;
    };

    const renderCatalog = (selectedSlug) => {
      modelSelect.replaceChildren();
      const auto = document.createElement('option');
      auto.value = '';
      auto.textContent = '自动选择模型';
      modelSelect.appendChild(auto);
      for (const family of families) {
        const option = document.createElement('option');
        option.value = family.id;
        option.textContent = family.label;
        modelSelect.appendChild(option);
      }
      const family = familyForSlug(selectedSlug);
      modelSelect.value = family?.id || '';
      renderVariants(family, selectedSlug);
    };

    const setDisabled = (disabled) => {
      modelSelect.disabled = disabled;
      variantSelect.disabled = disabled;
      refresh.disabled = disabled;
    };

    const applySlug = async (slug, label) => {
      if (applying) return;
      applying = true;
      setDisabled(true);
      try {
        report('正在切换模型…', 'working');
        const settings = await window.GecisNative.setRuntimeSettings(slug || '', '');
        currentSlug = settings?.model || null;
        report(`已切换到 ${label || '自动模型'}`, 'success');
      } catch (error) {
        report(error?.message || '模型切换失败', 'error');
        renderCatalog(currentSlug);
      } finally {
        applying = false;
        setDisabled(false);
      }
    };

    const loadCatalog = async ({ quiet = false } = {}) => {
      setDisabled(true);
      if (!quiet) modelSelect.innerHTML = '<option value="">读取模型…</option>';
      try {
        const [settings, catalog] = await Promise.all([
          window.GecisNative.getRuntimeSettings(),
          window.GecisNative.getAvailableModels(),
        ]);
        families = Array.isArray(catalog?.models) ? catalog.models : [];
        if (!families.length) throw new Error('上游没有返回可用模型');
        currentSlug = settings?.model || catalog?.selected || null;
        if (currentSlug && !familyForSlug(currentSlug)) {
          await window.GecisNative.setRuntimeSettings('', '');
          currentSlug = null;
          report('旧模型已不可用，已切换为自动模型', 'success');
        }
        renderCatalog(currentSlug);
      } catch (error) {
        modelSelect.replaceChildren();
        const option = document.createElement('option');
        option.value = '';
        option.textContent = '模型列表不可用';
        modelSelect.appendChild(option);
        variantSelect.hidden = true;
        if (!quiet) report(error?.message || '读取模型列表失败', 'error');
      } finally {
        setDisabled(false);
      }
    };

    modelSelect.addEventListener('change', async () => {
      if (!modelSelect.value) {
        variantSelect.hidden = true;
        await applySlug('', '自动模型');
        return;
      }
      const family = families.find((f) => f.id === modelSelect.value);
      const variant = renderVariants(family, null);
      if (variant) await applySlug(variant.slug, `${family.label}${variant.effort ? ` · ${variant.label}` : ''}`);
    });
    variantSelect.addEventListener('change', async () => {
      const family = families.find((f) => f.id === modelSelect.value);
      const variant = (family?.variants || []).find((v) => v.slug === variantSelect.value);
      if (variant) await applySlug(variant.slug, `${family.label} · ${variant.label}`);
    });
    refresh.addEventListener('click', () => loadCatalog());
    window.GecisBridgeReady?.then?.(() => loadCatalog({ quiet: true }));
  };

  window.GecisBridgeReady = Promise.resolve();
  installModelPicker();
})();