const messages = document.getElementById('messages');
const form = document.getElementById('form');
const input = document.getElementById('input');
const send = document.getElementById('send');
const status = document.getElementById('status');
const drawer = document.getElementById('drawer');
const drawerScrim = document.getElementById('drawerScrim');
const projectList = document.getElementById('projectList');
let active = null;
let statusResetTimer = null;
let historyInitialized = false;
let currentConversationId = null;
let currentProjectId = null;

function renderEmpty() {
  messages.innerHTML =
    '<section class="empty"><h1>想学什么？</h1><p>直接提问。支持 Markdown 与数学公式。</p></section>';
}
renderEmpty();

function setStatus(text, state = 'idle') {
  clearTimeout(statusResetTimer);
  status.textContent = text || '本地学习助手';
  status.dataset.state = state;
  if (state === 'success') statusResetTimer = setTimeout(() => setStatus('本地学习助手', 'idle'), 2200);
}

function escapeHtml(value) {
  return String(value).replace(/[&<>"']/g, (c) => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
  }[c]));
}

function protectMath(source) {
  const expressions = [];
  const tokenPrefix = 'GECISMATHTOKEN';
  const tokenSuffix = 'ENDTOKEN';
  const pattern = /\$\$[\s\S]*?\$\$|\\\[[\s\S]*?\\\]|\\\([\s\S]*?\\\)|\$[^$\n]+?\$/g;
  const text = source.replace(pattern, (match) => {
    const index = expressions.push(match) - 1;
    return `${tokenPrefix}${index}${tokenSuffix}`;
  });
  return { text, expressions, tokenPrefix, tokenSuffix };
}

function restoreMath(html, protectedMath) {
  const pattern = new RegExp(`${protectedMath.tokenPrefix}(\\d+)${protectedMath.tokenSuffix}`, 'g');
  return html.replace(pattern, (_, index) => escapeHtml(protectedMath.expressions[Number(index)] || ''));
}

function renderMath(node) {
  if (!window.renderMathInElement) return;
  renderMathInElement(node, {
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
  if (!window.marked || !window.DOMPurify) {
    node.textContent = text;
    return;
  }
  const math = protectMath(text);
  const dirty = window.marked.parse(math.text, { gfm: true, breaks: true });
  const clean = window.DOMPurify.sanitize(dirty, {
    USE_PROFILES: { html: true },
    FORBID_TAGS: ['img', 'iframe', 'object', 'embed', 'style', 'form', 'input', 'button', 'video', 'audio'],
  });
  node.innerHTML = restoreMath(clean, math);
  renderMath(node);
}

function renderUser(node, text) {
  node.textContent = text;
}

function renderMessage(node, role, text) {
  if (role === 'assistant') renderAssistant(node, text);
  else renderUser(node, text);
}

function addMessage(role, text, requestId, scroll = true) {
  messages.querySelector('.empty')?.remove();
  const row = document.createElement('section');
  row.className = `message ${role}`;
  if (requestId) row.dataset.requestId = requestId;
  const bubble = document.createElement('div');
  bubble.className = 'bubble';
  renderMessage(bubble, role, text);
  row.appendChild(bubble);
  messages.appendChild(row);
  if (scroll) window.scrollTo({ top: document.body.scrollHeight, behavior: 'smooth' });
  return row;
}

function beginAssistant(requestId) {
  const row = addMessage('assistant', '', requestId);
  row.classList.add('pending');
  active = { requestId, row, bubble: row.querySelector('.bubble'), text: '' };
  return active;
}

function finish() {
  send.disabled = false;
  if (status.dataset.state === 'working') setStatus('本地学习助手', 'idle');
  input.focus();
}

function openDrawer() {
  drawer.classList.add('open');
  drawerScrim.classList.add('open');
  drawer.setAttribute('aria-hidden', 'false');
}

function closeDrawer() {
  drawer.classList.remove('open');
  drawerScrim.classList.remove('open');
  drawer.setAttribute('aria-hidden', 'true');
}

function parseNativeSnapshot(raw) {
  return typeof raw === 'string' ? JSON.parse(raw) : raw;
}

function renderProjectList(snapshot) {
  currentConversationId = snapshot?.currentConversationId ?? null;
  currentProjectId = snapshot?.currentProjectId ?? currentProjectId;
  projectList.innerHTML = '';
  for (const project of snapshot?.projects || []) {
    const section = document.createElement('section');
    section.className = 'project';
    const head = document.createElement('div');
    head.className = 'project-head';
    const name = document.createElement('div');
    name.className = 'project-name';
    name.textContent = project.name;
    head.appendChild(name);
    const plus = document.createElement('button');
    plus.className = 'project-new-chat';
    plus.type = 'button';
    plus.textContent = '＋';
    plus.title = '在此项目中新建对话';
    plus.addEventListener('click', () => newConversation(project.id));
    head.appendChild(plus);
    section.appendChild(head);
    if (!project.conversations?.length) {
      const empty = document.createElement('div');
      empty.className = 'project-empty';
      empty.textContent = '暂无对话';
      section.appendChild(empty);
    } else {
      for (const conversation of project.conversations) {
        const item = document.createElement('button');
        item.type = 'button';
        item.className = 'conversation' + (conversation.id === currentConversationId ? ' current' : '');
        item.textContent = conversation.title;
        item.addEventListener('click', () => openConversation(conversation.id));
        section.appendChild(item);
      }
    }
    projectList.appendChild(section);
  }
}

function replaceConversation(snapshot) {
  active = null;
  send.disabled = false;
  messages.innerHTML = '';
  for (const message of snapshot?.messages || []) addMessage(message.role, message.content, null, false);
  if (!(snapshot?.messages || []).length) renderEmpty();
  else window.scrollTo({ top: document.body.scrollHeight });
}

function applySnapshot(raw, replace = false) {
  try {
    const snapshot = parseNativeSnapshot(raw);
    renderProjectList(snapshot);
    if (replace) replaceConversation(snapshot);
    return snapshot;
  } catch (err) {
    setStatus('历史记录读取失败', 'error');
    console.error(err);
    return null;
  }
}

async function newConversation(projectId) {
  if (active) {
    setStatus('请等待当前回答完成', 'error');
    return;
  }
  try {
    const raw = await window.GecisNative.newConversation(Number(projectId));
    applySnapshot(raw, true);
    closeDrawer();
    setStatus('新对话', 'idle');
  } catch (err) {
    setStatus(err.message || '无法新建对话', 'error');
  }
}

async function openConversation(conversationId) {
  if (active) {
    setStatus('请等待当前回答完成', 'error');
    return;
  }
  try {
    const raw = await window.GecisNative.openConversation(Number(conversationId));
    applySnapshot(raw, true);
    closeDrawer();
    setStatus('已打开历史对话', 'idle');
  } catch (err) {
    setStatus(err.message || '无法打开历史对话', 'error');
  }
}

async function createProject() {
  if (active) {
    setStatus('请等待当前回答完成', 'error');
    return;
  }
  const name = prompt('项目名称');
  if (name == null) return;
  const value = name.trim();
  if (!value) return;
  try {
    const raw = await window.GecisNative.createProject(value);
    const snapshot = applySnapshot(raw, true);
    if (snapshot?.currentProjectId) await newConversation(snapshot.currentProjectId);
  } catch (err) {
    setStatus(err.message || '无法新建项目', 'error');
  }
}

window.GecisChat = {
  onStatus(event) {
    if (event) setStatus(event.text, event.state || 'idle');
  },
  onHistory(snapshot) {
    renderProjectList(snapshot);
    if (!historyInitialized) {
      historyInitialized = true;
      if (!active) replaceConversation(snapshot);
    }
  },
  onResumeTurn(event) {
    if (!event?.requestId || active) return;
    const lastUser = [...messages.querySelectorAll('.message.user .bubble')].pop();
    if (!lastUser || lastUser.textContent !== event.text) addMessage('user', event.text || '');
    beginAssistant(event.requestId);
    send.disabled = true;
  },
  onNativeEvent(event) {
    if (!active || event.requestId !== active.requestId) {
      if (!active && (event.type === 'complete' || event.type === 'error')) finish();
      return;
    }
    if (event.type === 'delta') {
      active.text += event.text;
      renderAssistant(active.bubble, active.text);
      window.scrollTo({ top: document.body.scrollHeight });
      return;
    }
    active.row.classList.remove('pending');
    if (event.type === 'complete') {
      active.text = event.text || active.text;
      renderAssistant(active.bubble, active.text);
    } else if (event.type === 'error') {
      active.text = '发生错误：' + event.text;
      renderAssistant(active.bubble, active.text);
    }
    active = null;
    finish();
  },
};

document.getElementById('historyTrigger').addEventListener('click', openDrawer);
document.getElementById('closeDrawer').addEventListener('click', closeDrawer);
drawerScrim.addEventListener('click', closeDrawer);
document.getElementById('newProject').addEventListener('click', createProject);

form.addEventListener('submit', async (e) => {
  e.preventDefault();
  const text = input.value.trim();
  if (!text || active) return;
  const requestId = crypto.randomUUID?.() || `${Date.now()}-${Math.random()}`;
  addMessage('user', text);
  input.value = '';
  input.style.height = 'auto';
  send.disabled = true;
  setStatus('正在准备…', 'working');
  beginAssistant(requestId);
  try {
    if (!window.GecisNative?.sendMessage) throw new Error('原生 AI 桥尚未连接');
    await window.GecisNative.sendMessage(requestId, text);
  } catch (err) {
    window.GecisChat.onNativeEvent({ type: 'error', requestId, text: err.message });
  }
});

input.addEventListener('keydown', (e) => {
  if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
    e.preventDefault();
    form.requestSubmit();
  }
});
input.addEventListener('input', () => {
  input.style.height = 'auto';
  input.style.height = Math.min(input.scrollHeight, 160) + 'px';
});

window.GecisBridgeReady?.then?.(async () => {
  try {
    const raw = await window.GecisNative.getHistory();
    applySnapshot(raw, true);
  } catch (err) {
    setStatus(err.message || '历史记录不可用', 'error');
  }
});
