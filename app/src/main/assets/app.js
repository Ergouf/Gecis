const messages = document.getElementById('messages');
const form = document.getElementById('form');
const input = document.getElementById('input');
const send = document.getElementById('send');
const statusEl = document.getElementById('status');
const drawer = document.getElementById('drawer');
const drawerScrim = document.getElementById('drawerScrim');
const projectList = document.getElementById('projectList');
const menu = document.getElementById('menu');
const btnMenu = document.getElementById('btnMenu');
const heading = document.getElementById('conversationHeading');
const fenbiState = document.getElementById('fenbiState');
const fenbiAction = document.getElementById('fenbiAction');
const authState = document.getElementById('authState');
const authAction = document.getElementById('authAction');
const conversationMenu = document.getElementById('conversationMenu');

let active = null;
let statusResetTimer = null;
let historyInitialized = false;
let currentConversationId = null;
let currentProjectId = null;
let latestProjects = [];
let setupStatus = {
  platform: 'unknown', runtime: 'unknown', auth: 'unknown',
  knowledge: { available: false, name: null },
};
const pendingRequests = new Map();

const rendererReady = Promise.all([
  Promise.resolve().then(() => {
    if (!window.marked || !window.DOMPurify || !window.renderMathInElement) {
      throw new Error('富文本渲染组件未加载');
    }
  }),
  document.fonts?.ready || Promise.resolve(),
]).then(() => true).catch((error) => {
  console.error(error);
  return false;
});

function normalizeSetupStatus(raw) {
  const value = raw || {};
  const knowledge = value.knowledge || (Object.prototype.hasOwnProperty.call(value, 'hasFenbi') ? {
    available: Boolean(value.hasFenbi),
    name: value.hasFenbi ? 'fenbi.db' : null,
  } : setupStatus.knowledge);
  let auth = value.auth || setupStatus.auth || 'unknown';
  if (!value.auth && value.loggedIn === true) auth = 'connected';
  return {
    platform: value.platform || setupStatus.platform || 'unknown',
    runtime: value.runtime || (Object.prototype.hasOwnProperty.call(value, 'agyInstalled') ? (value.agyInstalled ? 'ready' : 'missing') : setupStatus.runtime),
    auth,
    knowledge,
  };
}

function applySetupStatus(raw) {
  setupStatus = normalizeSetupStatus(raw);
  document.body.dataset.platform = setupStatus.platform;
  const hasFenbi = Boolean(setupStatus.knowledge?.available);
  fenbiState.textContent = hasFenbi ? (setupStatus.knowledge.name || '已导入') : '未导入，可选';
  fenbiAction.textContent = hasFenbi ? '更换' : '导入';
  const authLabels = {
    connected: '已连接', checking: '正在检查…', required: '需要连接', unknown: '需要时连接',
  };
  authState.textContent = authLabels[setupStatus.auth] || authLabels.unknown;
  authAction.hidden = setupStatus.auth === 'connected' || setupStatus.auth === 'checking';
  syncDrawerMode();
}

async function refreshSetupStatus() {
  try {
    if (window.GecisNative?.getSetupStatus) applySetupStatus(await window.GecisNative.getSetupStatus());
  } catch (error) {
    console.warn('setup status unavailable', error);
  }
  return setupStatus;
}

function renderEmpty() {
  messages.innerHTML = '';
}

function setStatus(text, state = 'idle') {
  clearTimeout(statusResetTimer);
  statusEl.textContent = text || '';
  statusEl.dataset.state = state;
  if (state === 'success') statusResetTimer = setTimeout(() => setStatus('', 'idle'), 2600);
}

let turnClock = null;
let turnStartedAt = 0;

function stopTurnClock() {
  clearInterval(turnClock);
  turnClock = null;
}

function showTurnProgress(text) {
  const label = text || '正在思考…';
  setStatus(label, 'working');
  if (!active || active.text) return;
  let hint = active.bubble.querySelector('.turn-progress');
  if (!hint) {
    hint = document.createElement('div');
    hint.className = 'turn-progress';
    hint.setAttribute('aria-live', 'polite');
    active.bubble.appendChild(hint);
  }
  hint.textContent = label;
}

function startTurnClock() {
  stopTurnClock();
  turnStartedAt = Date.now();
  turnClock = setInterval(() => {
    if (!active || active.text) {
      stopTurnClock();
      return;
    }
    const current = statusEl.textContent || '';
    if (/查询|失败|错误|连接|准备|登录|回答/.test(current)) return;
    const elapsed = Math.round((Date.now() - turnStartedAt) / 1000);
    showTurnProgress(elapsed >= 8 ? '仍在思考…' : '正在思考…');
  }, 4000);
}

const providerModelSelect = document.getElementById('providerModelSelect');
const providerVariantSelect = document.getElementById('providerVariantSelect');
const refreshProviderModels = document.getElementById('refreshProviderModels');
let providerFamilies = [];
let currentModelSlug = null;
let applyingModel = false;

function familyForSlug(slug) {
  return providerFamilies.find((family) => (family.variants || []).some((variant) => variant.slug === slug));
}

function preferredVariant(family) {
  const variants = family?.variants || [];
  return variants.find((variant) => variant.effort === 'medium') || variants[0] || null;
}

function renderProviderVariants(family, selectedSlug) {
  if (!providerVariantSelect) return family?.variants?.[0] || null;
  const variants = family?.variants || [];
  providerVariantSelect.replaceChildren();
  if (variants.length <= 1) {
    providerVariantSelect.hidden = true;
    return variants[0] || null;
  }
  for (const variant of variants) {
    const option = document.createElement('option');
    option.value = variant.slug;
    option.textContent = variant.label || variant.effort || '默认';
    providerVariantSelect.appendChild(option);
  }
  const selected = variants.find((variant) => variant.slug === selectedSlug) || preferredVariant(family);
  if (selected) providerVariantSelect.value = selected.slug;
  providerVariantSelect.hidden = false;
  return selected;
}

function renderProviderCatalog(selectedSlug) {
  if (!providerModelSelect) return;
  providerModelSelect.replaceChildren();
  const auto = document.createElement('option');
  auto.value = '';
  auto.textContent = '自动选择模型';
  providerModelSelect.appendChild(auto);
  for (const family of providerFamilies) {
    const option = document.createElement('option');
    option.value = family.id;
    option.textContent = family.label;
    providerModelSelect.appendChild(option);
  }
  const family = familyForSlug(selectedSlug);
  providerModelSelect.value = family?.id || '';
  renderProviderVariants(family, selectedSlug);
}

function setProviderControlsDisabled(disabled) {
  if (providerModelSelect) providerModelSelect.disabled = disabled;
  if (providerVariantSelect) providerVariantSelect.disabled = disabled;
  if (refreshProviderModels) refreshProviderModels.disabled = disabled;
}

async function applyModelSlug(slug, label) {
  if (applyingModel || !window.GecisNative?.setRuntimeSettings) return;
  applyingModel = true;
  setProviderControlsDisabled(true);
  try {
    setStatus('正在切换模型…', 'working');
    const settings = await window.GecisNative.setRuntimeSettings(slug || '', '');
    currentModelSlug = settings?.model || null;
    setStatus(`已切换到 ${label || '自动模型'}`, 'success');
  } catch (error) {
    setStatus(error?.message || '模型切换失败', 'error');
    renderProviderCatalog(currentModelSlug);
  } finally {
    applyingModel = false;
    setProviderControlsDisabled(false);
  }
}

async function reloadProviderModels({ quiet = false } = {}) {
  if (!providerModelSelect || !window.GecisNative?.getAvailableModels) return;
  setProviderControlsDisabled(true);
  if (!quiet) providerModelSelect.innerHTML = '<option value="">读取模型…</option>';
  try {
    const [settings, catalog] = await Promise.all([
      window.GecisNative.getRuntimeSettings?.() || Promise.resolve({}),
      window.GecisNative.getAvailableModels(),
    ]);
    providerFamilies = Array.isArray(catalog?.models) ? catalog.models : [];
    if (!providerFamilies.length) throw new Error('上游没有返回可用模型');
    currentModelSlug = settings?.model || catalog?.selected || null;
    if (currentModelSlug && !familyForSlug(currentModelSlug)) {
      await window.GecisNative.setRuntimeSettings?.('', '');
      currentModelSlug = null;
      setStatus('旧模型已不可用，已切换为自动模型', 'success');
    }
    renderProviderCatalog(currentModelSlug);
  } catch (error) {
    providerModelSelect.replaceChildren();
    const option = document.createElement('option');
    option.value = '';
    option.textContent = '模型列表不可用';
    providerModelSelect.appendChild(option);
    if (providerVariantSelect) providerVariantSelect.hidden = true;
    if (!quiet) setStatus(error?.message || '读取模型列表失败', 'error');
  } finally {
    setProviderControlsDisabled(false);
  }
}

if (providerModelSelect && providerVariantSelect && refreshProviderModels) {
  providerModelSelect.addEventListener('change', async () => {
    if (!providerModelSelect.value) {
      providerVariantSelect.hidden = true;
      await applyModelSlug('', '自动模型');
      return;
    }
    const family = providerFamilies.find((item) => item.id === providerModelSelect.value);
    const variant = renderProviderVariants(family, null);
    if (variant) await applyModelSlug(variant.slug, `${family.label}${variant.effort ? ` · ${variant.label}` : ''}`);
  });
  providerVariantSelect.addEventListener('change', async () => {
    const family = providerFamilies.find((item) => item.id === providerModelSelect.value);
    const variant = (family?.variants || []).find((item) => item.slug === providerVariantSelect.value);
    if (variant) await applyModelSlug(variant.slug, `${family.label} · ${variant.label}`);
  });
  refreshProviderModels.addEventListener('click', () => reloadProviderModels());
}

function escapeHtml(value) {
  return String(value).replace(/[&<>"']/g, (c) => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
  }[c]));
}

function protectMath(source) {
  const expressions = [];
  const tokenPrefix = `GECISMATH${Math.random().toString(36).slice(2)}TOKEN`;
  const tokenSuffix = 'ENDTOKEN';
  const pattern = /\$\$[\s\S]*?\$\$|\\\[[\s\S]*?\\\]|\\\([\s\S]*?\\\)|\$[^$\n]+?\$/g;
  const text = String(source || '').replace(pattern, (match) => {
    const index = expressions.push(match) - 1;
    return `${tokenPrefix}${index}${tokenSuffix}`;
  });
  return { text, expressions, tokenPrefix, tokenSuffix };
}

function restoreMath(html, protectedMath) {
  const pattern = new RegExp(`${protectedMath.tokenPrefix}(\\d+)${protectedMath.tokenSuffix}`, 'g');
  return html.replace(pattern, (_, index) => escapeHtml(protectedMath.expressions[Number(index)] || ''));
}

function renderRichTextNow(node, text) {
  const math = protectMath(text);
  const dirty = window.marked.parse(math.text, { gfm: true, breaks: true });
  const clean = window.DOMPurify.sanitize(dirty, {
    USE_PROFILES: { html: true },
    FORBID_TAGS: ['iframe', 'object', 'embed', 'style', 'form', 'input', 'button', 'script'],
  });
  node.innerHTML = restoreMath(clean, math);
  window.renderMathInElement(node, {
    delimiters: [
      { left: '$$', right: '$$', display: true },
      { left: '\\[', right: '\\]', display: true },
      { left: '$', right: '$', display: false },
      { left: '\\(', right: '\\)', display: false },
    ],
    ignoredTags: ['script', 'noscript', 'style', 'textarea', 'pre', 'code', 'option'],
    throwOnError: false,
    trust: false,
    strict: 'warn',
  });
}

function renderAssistant(node, text) {
  const revision = Number(node.dataset.renderRevision || 0) + 1;
  node.dataset.renderRevision = String(revision);
  node.textContent = text || '';
  rendererReady.then((ready) => {
    if (!ready || Number(node.dataset.renderRevision) !== revision || !node.isConnected) return;
    renderRichTextNow(node, text || '');
  });
}

function addMessage(role, text, requestId, scroll = true) {
  messages.querySelector('.empty')?.remove();
  const row = document.createElement('section');
  row.className = `message ${role}`;
  if (requestId) row.dataset.requestId = requestId;
  const bubble = document.createElement('div');
  bubble.className = 'bubble';
  if (role === 'assistant') renderAssistant(bubble, text);
  else bubble.textContent = text;
  row.appendChild(bubble);
  messages.appendChild(row);
  if (scroll) window.scrollTo({ top: document.body.scrollHeight, behavior: 'smooth' });
  return row;
}

function beginAssistant(requestId, row = null) {
  const target = row || addMessage('assistant', '', requestId);
  target.className = 'message assistant pending';
  target.dataset.requestId = requestId;
  let bubble = target.querySelector('.bubble');
  if (!bubble) {
    target.innerHTML = '';
    bubble = document.createElement('div');
    bubble.className = 'bubble';
    target.appendChild(bubble);
  }
  bubble.innerHTML = '';
  active = { requestId, row: target, bubble, text: '' };
  showTurnProgress('正在思考…');
  startTurnClock();
  syncComposer();
  return active;
}

function finish() {
  stopTurnClock();
  active = null;
  syncComposer();
  if (statusEl.dataset.state === 'working') setStatus('', 'idle');
  input.focus();
}

function syncComposer() {
  send.disabled = Boolean(active) || !input.value.trim();
}

function defaultActions(kind) {
  if (kind === 'auth') return [{ id: 'login', label: '去登录', primary: true }];
  if (kind === 'runtime') return [
    { id: 'install', label: '安装运行环境', primary: true },
    { id: 'copy_install', label: '复制安装命令' },
  ];
  if (kind === 'knowledge') return [{ id: 'import', label: '重新选择', primary: true }];
  return [{ id: 'retry', label: '重试', primary: true }];
}

function renderActionCard(row, event) {
  row.className = 'message assistant';
  row.dataset.requestId = event.requestId || '';
  row.innerHTML = '';
  const card = document.createElement('div');
  card.className = 'action-card';
  card.dataset.tone = event.kind === 'auth' || event.kind === 'runtime' ? 'warning' : 'error';
  const title = document.createElement('h3');
  title.className = 'action-title';
  title.textContent = event.title || ({ auth: '需要连接账号', runtime: '缺少运行环境', knowledge: '题库不可用', network: '暂时无法连接' }[event.kind] || '需要处理');
  const detail = document.createElement('p');
  detail.className = 'action-message';
  detail.textContent = event.message || '完成下面的操作后可以继续。';
  const buttons = document.createElement('div');
  buttons.className = 'action-buttons';
  const actions = event.actions?.length ? event.actions : defaultActions(event.kind);
  const labels = { login: '去登录', retry: '重试', install: '安装运行环境', copy_install: '复制安装命令', import: '重新选择' };
  for (const actionValue of actions) {
    const action = typeof actionValue === 'string' ? { id: actionValue, label: labels[actionValue] || actionValue, primary: actions.length === 1 } : actionValue;
    const button = document.createElement('button');
    button.type = 'button';
    button.className = `action-button${action.primary ? ' primary' : ''}`;
    button.textContent = action.label || action.id;
    button.addEventListener('click', () => handleCardAction(button, action.id, event));
    buttons.appendChild(button);
  }
  card.append(title, detail, buttons);
  row.appendChild(card);
  window.scrollTo({ top: document.body.scrollHeight, behavior: 'smooth' });
}

async function handleCardAction(button, action, event) {
  const row = button.closest('.message');
  button.closest('.action-buttons')?.querySelectorAll('button').forEach((item) => { item.disabled = true; });
  try {
    if (action === 'login') {
      authState.textContent = '正在连接…';
      authAction.hidden = true;
      setStatus('正在打开 Google 登录…', 'working');
      beginAssistant(event.requestId, row);
      await window.GecisNative.startLogin(event.requestId || null);
    } else if (action === 'retry') {
      const text = pendingRequests.get(event.requestId);
      if (!text) throw new Error('原问题已不可用，请重新发送');
      beginAssistant(event.requestId, button.closest('.message'));
      setStatus('正在重试…', 'working');
      await window.GecisNative.retryMessage(event.requestId, text);
    } else if (action === 'install') {
      setStatus('正在安装运行环境…', 'working');
      await window.GecisNative.installRuntime();
    } else if (action === 'copy_install') {
      await navigator.clipboard.writeText('irm https://antigravity.google/cli/install.ps1 | iex');
      setStatus('已复制安装命令', 'success');
    } else if (action === 'import') {
      await importFenbi();
    }
  } catch (error) {
    setStatus(error.message || '操作失败', 'error');
    if (active?.requestId === event.requestId && row) {
      finish();
      renderActionCard(row, { ...event, message: error.message || event.message });
    } else {
      button.closest('.action-buttons')?.querySelectorAll('button').forEach((item) => { item.disabled = false; });
    }
  }
}

function isPersistentSidebar() {
  return setupStatus.platform !== 'android' && matchMedia('(min-width: 860px)').matches;
}
function syncDrawerMode() {
  const persistent = isPersistentSidebar();
  drawer.setAttribute('aria-hidden', persistent || drawer.classList.contains('open') ? 'false' : 'true');
  if (persistent) {
    drawer.classList.remove('open');
    drawerScrim.classList.remove('open');
  }
}
function openDrawer() {
  if (isPersistentSidebar()) return;
  drawer.classList.add('open');
  drawerScrim.classList.add('open');
  drawer.setAttribute('aria-hidden', 'false');
  drawer.querySelector('button')?.focus();
}
function closeDrawer() {
  if (isPersistentSidebar()) return;
  drawer.classList.remove('open');
  drawerScrim.classList.remove('open');
  drawer.setAttribute('aria-hidden', 'true');
  document.getElementById('historyTrigger').focus();
}
function closeMenu() { menu.classList.remove('open'); btnMenu.setAttribute('aria-expanded', 'false'); }
function toggleMenu() { const open = !menu.classList.contains('open'); menu.classList.toggle('open', open); btnMenu.setAttribute('aria-expanded', String(open)); }

function formatRelativeTime(ts) {
  if (!ts) return '';
  const t = Number(ts);
  if (!Number.isFinite(t) || t <= 0) return '';
  const ms = t > 1e12 ? t : t * 1000;
  const minutes = Math.floor((Date.now() - ms) / 60000);
  if (minutes < 1) return '刚刚';
  if (minutes < 60) return `${minutes} 分钟`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours} 小时`;
  const days = Math.floor(hours / 24);
  return days < 30 ? `${days} 天` : new Date(ms).toLocaleDateString();
}
function previewText(raw) {
  const text = String(raw || '').replace(/[#*_`>~\[\]()]/g, ' ').replace(/\s+/g, ' ').trim();
  return text.length > 58 ? `${text.slice(0, 58)}…` : text;
}
function parseNativeSnapshot(raw) { return typeof raw === 'string' ? JSON.parse(raw) : raw; }

function iconSvg(name) {
  const paths = {
    more: '<circle cx="5" cy="12" r="1" fill="currentColor" stroke="none"/><circle cx="12" cy="12" r="1" fill="currentColor" stroke="none"/><circle cx="19" cy="12" r="1" fill="currentColor" stroke="none"/>',
    folder: '<path d="M3 18V6a2 2 0 0 1 2-2h5l2 3h7a2 2 0 0 1 2 2v9a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2Z"/>',
    trash: '<path d="M4 7h16M9 7V4h6v3M7 7l1 13h8l1-13M10 11v5M14 11v5"/>',
    plus: '<path d="M12 5v14M5 12h14"/>',
  };
  return `<svg class="ui-icon" viewBox="0 0 24 24" aria-hidden="true">${paths[name] || ''}</svg>`;
}

function closeConversationMenu() {
  conversationMenu.classList.remove('open');
  conversationMenu.setAttribute('aria-hidden', 'true');
  document.querySelectorAll('.conversation-more[aria-expanded="true"]').forEach((button) => button.setAttribute('aria-expanded', 'false'));
}

function openConversationMenu(event, conversation, sourceProjectId) {
  event.preventDefault();
  event.stopPropagation();
  closeMenu();
  const trigger = event.currentTarget;
  const targets = latestProjects.filter((project) => Number(project.id) !== Number(sourceProjectId));
  conversationMenu.innerHTML = '<div class="conversation-menu-label">移动到分类</div>';
  if (targets.length) {
    for (const project of targets) {
      const move = document.createElement('button');
      move.type = 'button';
      move.innerHTML = `${iconSvg('folder')}<span></span>`;
      move.querySelector('span').textContent = project.name;
      move.addEventListener('click', () => moveConversation(conversation.id, project.id));
      conversationMenu.appendChild(move);
    }
  } else {
    const none = document.createElement('button');
    none.type = 'button'; none.disabled = true; none.textContent = '暂无其他分类';
    conversationMenu.appendChild(none);
  }
  const divider = document.createElement('div'); divider.className = 'sep';
  const remove = document.createElement('button');
  remove.type = 'button'; remove.className = 'danger';
  remove.innerHTML = `${iconSvg('trash')}<span>删除会话</span>`;
  remove.addEventListener('click', () => deleteConversation(conversation));
  conversationMenu.append(divider, remove);
  conversationMenu.classList.add('open');
  conversationMenu.setAttribute('aria-hidden', 'false');
  trigger.setAttribute('aria-expanded', 'true');
  const rect = trigger.getBoundingClientRect();
  const menuWidth = 218;
  const left = Math.max(8, Math.min(innerWidth - menuWidth - 8, rect.right - menuWidth));
  conversationMenu.style.left = `${left}px`;
  conversationMenu.style.top = `${Math.min(innerHeight - conversationMenu.offsetHeight - 8, rect.bottom + 4)}px`;
  conversationMenu.querySelector('button:not(:disabled)')?.focus();
}

function renderProjectList(snapshot) {
  currentConversationId = snapshot?.currentConversationId ?? null;
  currentProjectId = snapshot?.currentProjectId ?? currentProjectId;
  latestProjects = snapshot?.projects || [];
  closeConversationMenu();
  projectList.innerHTML = '';
  let currentTitle = '新对话';
  for (const project of snapshot?.projects || []) {
    const section = document.createElement('section');
    section.className = 'project';
    const head = document.createElement('div');
    head.className = 'project-head';
    const name = document.createElement('div');
    name.className = 'project-name';
    name.innerHTML = `${iconSvg('folder')}<span></span>`;
    name.querySelector('span').textContent = project.name;
    const plus = document.createElement('button');
    plus.className = 'project-new-chat'; plus.type = 'button'; plus.innerHTML = iconSvg('plus'); plus.title = '在此分类中新建对话';
    plus.addEventListener('click', () => newConversation(project.id));
    head.append(name, plus); section.appendChild(head);
    if (!project.conversations?.length) {
      const empty = document.createElement('div'); empty.className = 'project-empty'; empty.textContent = '暂无对话'; section.appendChild(empty);
    } else {
      for (const conversation of project.conversations) {
        if (conversation.id === currentConversationId) currentTitle = conversation.title || '新对话';
        const row = document.createElement('div');
        row.className = `conversation-row${conversation.id === currentConversationId ? ' current' : ''}`;
        const item = document.createElement('button');
        item.type = 'button'; item.className = 'conversation';
        const title = document.createElement('div'); title.className = 'conversation-title'; title.textContent = conversation.title || '未命名会话';
        const meta = document.createElement('div'); meta.className = 'conversation-meta';
        const preview = document.createElement('span'); preview.className = 'conversation-preview'; preview.textContent = previewText(conversation.preview) || '空会话';
        const time = document.createElement('span'); time.className = 'conversation-time'; time.textContent = formatRelativeTime(conversation.updatedAt);
        meta.appendChild(preview); if (time.textContent) meta.appendChild(time);
        const more = document.createElement('button');
        more.type = 'button'; more.className = 'conversation-more'; more.title = '会话操作'; more.setAttribute('aria-label', `${conversation.title || '会话'}的操作`); more.setAttribute('aria-haspopup', 'menu'); more.setAttribute('aria-expanded', 'false'); more.innerHTML = iconSvg('more');
        more.addEventListener('click', (event) => openConversationMenu(event, conversation, project.id));
        item.append(title, meta); item.addEventListener('click', () => openConversation(conversation.id));
        row.append(item, more); section.appendChild(row);
      }
    }
    projectList.appendChild(section);
  }
  heading.textContent = currentTitle;
}

function replaceConversation(snapshot) {
  active = null;
  messages.innerHTML = '';
  for (const message of snapshot?.messages || []) addMessage(message.role, message.content, null, false);
  if (!(snapshot?.messages || []).length) renderEmpty();
  else rendererReady.then(() => window.scrollTo({ top: document.body.scrollHeight }));
  syncComposer();
}
function applySnapshot(raw, replace = false) {
  try {
    const snapshot = parseNativeSnapshot(raw);
    renderProjectList(snapshot);
    if (replace) replaceConversation(snapshot);
    return snapshot;
  } catch (error) {
    setStatus('历史记录读取失败', 'error');
    console.error(error);
    return null;
  }
}

async function newConversation(projectId) {
  if (active) return setStatus('请等待当前回答完成', 'error');
  try { applySnapshot(await window.GecisNative.newConversation(Number(projectId)), true); closeDrawer(); }
  catch (error) { setStatus(error.message || '无法新建对话', 'error'); }
}
async function openConversation(conversationId) {
  if (active) return setStatus('请等待当前回答完成', 'error');
  try { applySnapshot(await window.GecisNative.openConversation(Number(conversationId)), true); closeDrawer(); }
  catch (error) { setStatus(error.message || '无法打开会话', 'error'); }
}
async function createProject() {
  if (active) return setStatus('请等待当前回答完成', 'error');
  const value = prompt('分类名称')?.trim();
  if (!value) return;
  try { const snapshot = applySnapshot(await window.GecisNative.createProject(value), true); if (snapshot?.currentProjectId) await newConversation(snapshot.currentProjectId); }
  catch (error) { setStatus(error.message || '无法新建分类', 'error'); }
}
async function moveConversation(conversationId, projectId) {
  closeConversationMenu();
  if (active) return setStatus('请等待当前回答完成', 'error');
  try {
    applySnapshot(await window.GecisNative.moveConversation(Number(conversationId), Number(projectId)), false);
    setStatus('已移动到分类', 'success');
  } catch (error) { setStatus(error.message || '无法移动会话', 'error'); }
}
async function deleteConversation(conversation) {
  closeConversationMenu();
  if (active) return setStatus('请等待当前回答完成', 'error');
  if (!confirm(`删除“${conversation.title || '未命名会话'}”？\n此操作无法撤销。`)) return;
  try {
    applySnapshot(await window.GecisNative.deleteConversation(Number(conversation.id)), Number(conversation.id) === Number(currentConversationId));
    setStatus('会话已删除', 'success');
  } catch (error) { setStatus(error.message || '无法删除会话', 'error'); }
}
async function importFenbi() {
  setStatus('请选择 fenbi.db…', 'working');
  const result = await window.GecisNative.importFenbi();
  if (result === 'cancelled') setStatus('', 'idle');
  setTimeout(refreshSetupStatus, 500);
}

window.GecisChat = {
  onStatus(event) {
    if (!event) return;
    if (event.state === 'success' && /登录|账号.*连接/.test(event.text || '')) {
      refreshSetupStatus();
      reloadProviderModels({ quiet: true });
    }
    if (event.state === 'success' && /导入成功/.test(event.text || '')) refreshSetupStatus();
    if (active && (event.state === 'idle' || event.state === 'success')) return;
    if (active && event.state === 'working') {
      showTurnProgress(event.text || '正在思考…');
      return;
    }
    setStatus(event.text, event.state || 'idle');
    if (event.state === 'error' && /导入失败|文件选择器/.test(event.text || '')) {
      const row = addMessage('assistant', '', '');
      renderActionCard(row, {
        type: 'action_required', kind: 'knowledge', title: '题库没有导入',
        message: event.text, actions: [{ id: 'import', label: '重新选择', primary: true }],
      });
    }
  },
  onSetupStatus(event) { applySetupStatus(event); },
  onHistory(snapshot, forceReplace = false) {
    renderProjectList(snapshot);
    if (active) return;
    if (forceReplace || !historyInitialized) { historyInitialized = true; replaceConversation(snapshot); }
  },
  onResumeTurn(event) {
    if (!event?.requestId || active) return;
    pendingRequests.set(event.requestId, event.text || '');
    const lastUser = [...messages.querySelectorAll('.message.user .bubble')].pop();
    if (!lastUser || lastUser.textContent !== event.text) addMessage('user', event.text || '');
    beginAssistant(event.requestId);
  },
  onNativeEvent(event) {
    if (!event) return;
    if (event.type === 'setup_status') {
      applySetupStatus(event);
      if (event.auth === 'connected') reloadProviderModels({ quiet: true });
      return;
    }
    if (event.type === 'runtime_hint') return;
    const matchesActive = active && (!event.requestId || event.requestId === active.requestId);
    if (event.type === 'action_required') {
      const row = matchesActive ? active.row : addMessage('assistant', '', event.requestId);
      renderActionCard(row, event);
      if (matchesActive) finish();
      return;
    }
    if (!matchesActive) {
      if (event.type === 'error' && event.text) {
        const row = addMessage('assistant', '', event.requestId);
        renderActionCard(row, { type: 'action_required', requestId: event.requestId, kind: 'network', title: '没有完成回答', message: event.text });
      }
      return;
    }
    if (event.type === 'progress') {
      showTurnProgress(event.text || '正在思考…');
      return;
    }
    if (event.type === 'delta') {
      active.bubble.querySelector('.turn-progress')?.remove();
      active.text += event.text || '';
      renderAssistant(active.bubble, active.text);
      window.scrollTo({ top: document.body.scrollHeight });
      return;
    }
    active.row.classList.remove('pending');
    if (event.type === 'complete') {
      active.text = event.text || active.text;
      if (!String(active.text || '').trim()) {
        const row = active.row;
        const requestId = active.requestId;
        finish();
        renderActionCard(row, {
          type: 'action_required',
          requestId,
          kind: 'network',
          title: '没有生成内容',
          message: '题库查询后没有返回回答，请重试。',
        });
        return;
      }
      renderAssistant(active.bubble, active.text);
      pendingRequests.delete(active.requestId);
      finish();
    } else if (event.type === 'error') {
      const row = active.row;
      const requestId = active.requestId;
      finish();
      renderActionCard(row, { type: 'action_required', requestId, kind: 'network', title: '没有完成回答', message: event.text || '请检查网络后重试。' });
    }
  },
};

document.getElementById('historyTrigger').addEventListener('click', openDrawer);
document.getElementById('closeDrawer').addEventListener('click', closeDrawer);
drawerScrim.addEventListener('click', closeDrawer);
document.getElementById('newProject').addEventListener('click', createProject);
document.getElementById('newConversation').addEventListener('click', () => newConversation(currentProjectId));
fenbiAction.addEventListener('click', () => importFenbi().catch((error) => setStatus(error.message || '导入失败', 'error')));
authAction.addEventListener('click', async () => {
  try { authState.textContent = '正在连接…'; authAction.hidden = true; await window.GecisNative.startLogin(null); }
  catch (error) { authAction.hidden = false; authState.textContent = '连接失败'; setStatus(error.message || '无法登录', 'error'); }
});
btnMenu.addEventListener('click', (event) => { event.stopPropagation(); toggleMenu(); });
document.addEventListener('click', () => { closeMenu(); closeConversationMenu(); });
menu.addEventListener('click', (event) => event.stopPropagation());
conversationMenu.addEventListener('click', (event) => event.stopPropagation());
projectList.addEventListener('scroll', closeConversationMenu, { passive: true });
document.addEventListener('keydown', (event) => { if (event.key === 'Escape') { closeMenu(); closeConversationMenu(); closeDrawer(); } });
addEventListener('resize', syncDrawerMode);

menu.addEventListener('click', async (event) => {
  const button = event.target.closest('button[data-action]');
  if (!button) return;
  closeMenu();
  try {
    const action = button.dataset.action;
    if (action === 'copyId') {
      const meta = await window.GecisNative.getConversationId();
      if (!meta?.agyConversationId) throw new Error('发送一条消息后才能复制会话 ID');
      await navigator.clipboard.writeText(String(meta.agyConversationId));
      setStatus('已复制会话 ID', 'success');
    } else if (action === 'resumeConversation') {
      const value = prompt('粘贴共享会话 ID')?.trim();
      if (!value) return;
      setStatus('正在接入会话…', 'working');
      applySnapshot(await window.GecisNative.resumeConversation(value), true);
    } else if (action === 'exportMd' || action === 'exportHtml') {
      const result = await window.GecisNative.exportConversation(action === 'exportMd' ? 'md' : 'html');
      setStatus(result === 'cancelled' ? '' : '已导出', result === 'cancelled' ? 'idle' : 'success');
    }
  } catch (error) { setStatus(error.message || '操作失败', 'error'); }
});

function extractResumeConversationId(raw) {
  const text = String(raw || '').trim();
  const uuid = text.match(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i);
  if (!uuid) return null;
  const id = uuid[0];
  const hasHint = /续聊|继续|对话|会话|resume|continue|session/i.test(text);
  const rest = text.replace(id, ' ').replace(/续聊|继续|对话|会话|线程|resume|continue|session|id|ID|这个|一下|，|,|。|\.|：|:|\s+/gi, '').trim();
  return rest.length === 0 || (hasHint && rest.length < 24) ? id : null;
}

form.addEventListener('submit', async (event) => {
  event.preventDefault();
  const text = input.value.trim();
  if (!text || active) return;
  const resumeId = extractResumeConversationId(text);
  if (resumeId) {
    input.value = ''; syncComposer(); setStatus('正在接入会话…', 'working');
    try { applySnapshot(await window.GecisNative.resumeConversation(resumeId), true); setStatus('已接入共享会话', 'success'); }
    catch (error) { setStatus(error.message || '续聊失败', 'error'); }
    return;
  }
  const requestId = crypto.randomUUID?.() || `${Date.now()}-${Math.random()}`;
  pendingRequests.set(requestId, text);
  addMessage('user', text);
  input.value = ''; input.style.height = 'auto';
  beginAssistant(requestId);
  showTurnProgress('正在准备…');
  try { await window.GecisNative.sendMessage(requestId, text); }
  catch (error) { window.GecisChat.onNativeEvent({ type: 'error', requestId, text: error.message }); }
});
input.addEventListener('keydown', (event) => { if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) { event.preventDefault(); form.requestSubmit(); } });
input.addEventListener('input', () => { input.style.height = 'auto'; input.style.height = `${Math.min(input.scrollHeight,160)}px`; syncComposer(); });

renderEmpty();
syncComposer();
window.GecisBridgeReady?.then?.(async () => {
  try {
    await refreshSetupStatus();
    applySnapshot(await window.GecisNative.getHistory(), !historyInitialized);
    historyInitialized = true;
  } catch (error) { setStatus(error.message || '历史记录不可用', 'error'); }
  reloadProviderModels({ quiet: true });
});
