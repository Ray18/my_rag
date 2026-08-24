/* ============================================================
 * My RAG 前端：知识库问答（多轮对话，SSE 流式） + 文档上传
 * ============================================================ */
(() => {
  'use strict';

  // ===== DOM 引用 =====
  const $ = (sel) => document.querySelector(sel);
  const messagesEl = $('#messages');
  const chatInput = $('#chatInput');
  const sendBtn = $('#sendBtn');
  const newChatBtn = $('#newChatBtn');
  const dropZone = $('#dropZone');
  const fileInput = $('#fileInput');
  const uploadStatus = $('#uploadStatus');
  const docList = $('#docList');
  const docEmpty = $('#docEmpty');
  const docCount = $('#docCount');
  const toastEl = $('#toast');

  // ===== 状态 =====
  const MAX_HISTORY_TURNS = 8;   // 发送给后端的最近对话轮数（GET URL 长度限制）
  const ALLOWED_EXT = ['.pdf', '.doc', '.docx','.xls','.xlsx','.txt'];
  const MAX_SIZE = 50 * 1024 * 1024;

  let history = [];              // [{role:'user'|'assistant', content}, ...]
  let isStreaming = false;
  let abortController = null;

  // ============================================================
  // 迷你 Markdown 渲染器（无依赖，防 XSS：先转义再还原受保护内容）
  // 临时占位符用私有区字符 ，模型输出中基本不会出现
  // ============================================================
  function escapeHtml(s) {
    return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }

  /** 行内样式：行内代码（保护）→ 粗体/斜体 → 链接 → 还原代码 */
  function inline(src) {
    const codes = [];
    src = src.replace(/`([^`\n]+)`/g, (m, c) => {
      codes.push(c);
      return 'I' + (codes.length - 1) + '';
    });
    src = src.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
    src = src.replace(/(^|[^*])\*([^*\n]+)\*/g, '$1<em>$2</em>');
    src = src.replace(/\[([^\]]+)\]\(((?:https?:\/\/|mailto:)[^)\s]+)\)/g,
      '<a href="$2" target="_blank" rel="noopener noreferrer">$1</a>');
    src = src.replace(/[]I(\d+)[]/g, (m, i) => '<code>' + escapeHtml(codes[+i]) + '</code>');
    return src;
  }

  /** 块级渲染：围栏代码块 → 标题 → 引用 → 列表 → 段落 */
  function renderMarkdown(src) {
    const blocks = [];
    src = src.replace(/```(\w*)[\r\n]*([\s\S]*?)```/g, (m, lang, code) => {
      blocks.push({ lang: lang || '', code: code.replace(/\n$/, '') });
      return 'F' + (blocks.length - 1) + '';
    });

    const lines = escapeHtml(src).split('\n');
    let html = '';
    let inList = false;
    let inPara = false;

    const closeList = () => { if (inList) { html += '</ul>'; inList = false; } };
    const closePara = () => { if (inPara) { html += '</p>'; inPara = false; } };

    for (const raw of lines) {
      const line = raw.trimEnd();
      if (line === '') { closeList(); closePara(); continue; }

      // 围栏代码块
      const fence = line.match(/^[]F(\d+)[]$/);
      if (fence) {
        closeList(); closePara();
        const b = blocks[+fence[1]];
        html += '<div class="code-block">'
              + '<div class="code-head"><span>' + escapeHtml(b.lang || 'code') + '</span>'
              + '<button class="copy-btn" data-code="' + encodeURIComponent(b.code) + '">复制</button></div>'
              + '<pre><code>' + escapeHtml(b.code) + '</code></pre></div>';
        continue;
      }

      // 标题
      const heading = line.match(/^(#{1,3})\s+(.*)$/);
      if (heading) {
        closeList(); closePara();
        const lvl = heading[1].length;
        html += '<h' + lvl + '>' + inline(heading[2]) + '</h' + lvl + '>';
        continue;
      }

      // 引用（转义后 > 变成 &gt;）
      const quote = line.match(/^\s*&gt;[\s]?(.*)$/);
      if (quote) {
        closeList(); closePara();
        html += '<blockquote>' + inline(quote[1]) + '</blockquote>';
        continue;
      }

      // 列表
      const li = line.match(/^\s*(?:[-*]|\d+\.)\s+(.*)$/);
      if (li) {
        if (!inList) { closePara(); html += '<ul>'; inList = true; }
        html += '<li>' + inline(li[1]) + '</li>';
        continue;
      }

      // 段落 / 换行
      closeList();
      if (!inPara) { html += '<p>'; inPara = true; } else { html += '<br>'; }
      html += inline(line);
    }
    closeList(); closePara();
    return html;
  }

  // ============================================================
  // 消息渲染
  // ============================================================
  function scrollToBottom() {
    messagesEl.scrollTop = messagesEl.scrollHeight;
  }

  function addMessage(role, text) {
    const wrap = document.createElement('div');
    wrap.className = 'msg ' + (role === 'user' ? 'msg-user' : 'msg-ai');

    const bubble = document.createElement('div');
    bubble.className = 'bubble';

    if (role === 'user') {
      bubble.textContent = text;
      wrap.appendChild(bubble);
    } else {
      bubble.classList.add('md');
      const avatar = document.createElement('div');
      avatar.className = 'avatar';
      avatar.textContent = 'AI';
      bubble.innerHTML = renderMarkdown(text);
      wrap.append(avatar, bubble);
    }

    messagesEl.appendChild(wrap);
    scrollToBottom();
    return { wrap, bubble };
  }

  const TYPING_HTML = '<div class="typing"><span></span><span></span><span></span></div>';

  function showTypingBubble() {
    return addMessage('assistant', '').bubble;
  }

  // ============================================================
  // 问答流程
  // ============================================================
  async function send() {
    const question = chatInput.value.trim();
    if (!question || isStreaming) return;

    // 用户消息入列
    addMessage('user', question);
    chatInput.value = '';
    autoResize();
    history.push({ role: 'user', content: question });

    // 发送给后端的历史 = 除当前问题外的最近 N 轮
    const historyToSend = history.slice(0, -1).slice(-MAX_HISTORY_TURNS * 2);

    // 助手气泡 + 思考中
    const aiBubble = showTypingBubble();
    let modelText = '';      // 模型实际输出（记入历史用）
    let full = '';           // 展示文本（含停止/错误提示）
    let renderTimer = null;

    const render = () => {
      aiBubble.innerHTML = modelText ? renderMarkdown(modelText) : TYPING_HTML;
      scrollToBottom();
    };
    const scheduleRender = () => {
      if (renderTimer) return;
      renderTimer = setTimeout(() => { renderTimer = null; render(); }, 30);
    };

    isStreaming = true;
    setStreamingUI(true);
    abortController = new AbortController();

      const params = {
          question: question,
          history: JSON.stringify(historyToSend),
          source:  ""
      };

    const url = '/rag/query/stream';
      try {
        const res = await fetch(url, {
            signal: abortController.signal,
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            },
            body: JSON.stringify(params)
        });
      if (!res.ok) {
        const body = await res.text();
        throw new Error('HTTP ' + res.status + (body ? '（' + body.slice(0, 120) + '）' : ''));
      }
      if (!res.body) throw new Error('浏览器不支持流式响应');

      const reader = res.body.getReader();
      const decoder = new TextDecoder('utf-8');
      let buffer = '';

      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });

        // 按 SSE 空行分帧，取 data: 行
        let sep;
        while ((sep = buffer.indexOf('\n\n')) >= 0) {
          const eventText = buffer.slice(0, sep);
          buffer = buffer.slice(sep + 2);
          const dataLine = eventText.split('\n').find((l) => l.startsWith('data:'));
          if (!dataLine) continue;
          const payload = dataLine.slice(5).trim();
          if (!payload) continue;

          let obj;
          try { obj = JSON.parse(payload); } catch { continue; }

          if (obj.error) {
            full += '\n\n⚠️ ' + String(obj.error);
          } else if (typeof obj.content === 'string') {
            modelText += obj.content;
            full += obj.content;
          }
          scheduleRender();
        }
      }
    } catch (err) {
      if (err.name === 'AbortError') {
        full += '\n\n⏹️ 已停止生成';
      } else {
        full += '\n\n⚠️ 请求失败：' + err.message;
      }
    } finally {
      if (renderTimer) { clearTimeout(renderTimer); renderTimer = null; }
      aiBubble.innerHTML = full
        ? renderMarkdown(full)
        : '<p><em>（没有收到回复）</em></p>';
      scrollToBottom();

      // 仅当本次会话还在（未被「新对话」重置）时，把助手回复计入多轮历史
      if (history.length && history[history.length - 1].role === 'user') {
        history.push({ role: 'assistant', content: modelText.trim() || full.trim() || '（没有收到回复）' });
      }
      isStreaming = false;
      setStreamingUI(false);
      abortController = null;
      chatInput.focus();
    }
  }

  function setStreamingUI(on) {
    sendBtn.textContent = on ? '停止' : '发送';
    sendBtn.classList.toggle('is-stop', on);
  }

  // ============================================================
  // 文档上传
  // ============================================================
  function extOf(name) {
    const i = name.lastIndexOf('.');
    return i < 0 ? '' : name.slice(i).toLowerCase();
  }

  function addUploadItem(name, state, detail) {
    const el = document.createElement('div');
    el.className = 'upload-item ' + state;
    el.innerHTML = '<span class="upload-name">' + escapeHtml(name) + '</span>'
                 + '<span class="upload-state">' + escapeHtml(detail) + '</span>';
    uploadStatus.appendChild(el);
    return el;
  }

  async function uploadFile(file) {
    const ext = extOf(file.name);
    if (!ALLOWED_EXT.includes(ext)) {
      showToast('不支持的文件类型：' + (ext || '未知') + '，仅支持 PDF / Word / Excel /TXT');
      return;
    }
    if (file.size > MAX_SIZE) {
      showToast('文件超过 50MB 限制：' + file.name);
      return;
    }

    const item = addUploadItem(file.name, 'uploading', '上传中…');

    try {
      const fd = new FormData();
      fd.append('file', file);

      const res = await fetch('/rag/ingest', { method: 'POST', body: fd });
      const body = await res.json().catch(() => null);
      if (!res.ok) throw new Error((body && body.message) || 'HTTP ' + res.status);

      item.classList.remove('uploading');
      item.classList.add('ok');
      item.querySelector('.upload-state').textContent = body && body.replaced ? '✓ 已替换' : '✓ 已上传';
      showToast((body && body.message) || '《' + file.name + '》上传并向量化成功');
      await loadDocs();
    } catch (err) {
      item.classList.remove('uploading');
      item.classList.add('err');
      item.querySelector('.upload-state').textContent = '✗ 失败';
      showToast('《' + file.name + '》上传失败：' + err.message.slice(0, 80));
    }
  }

  // ============================================================
  // 文档列表（以服务端为准：列表 + 删除 + 数量）
  // ============================================================
  async function loadDocs() {
    try {
      const res = await fetch('/rag/docs');
      if (!res.ok) throw new Error('HTTP ' + res.status);
      const docs = await res.json();
      renderDocs(Array.isArray(docs) ? docs : []);
    } catch (err) {
      showToast('加载文档列表失败：' + err.message.slice(0, 80));
    }
  }

  function renderDocs(docs) {
    docList.innerHTML = '';
    docEmpty.hidden = docs.length > 0;
    docCount.hidden = docs.length === 0;
    if (docs.length > 0) docCount.textContent = docs.length;

    for (const d of docs) {
      const li = document.createElement('li');

      const icon = document.createElement('span');
      icon.className = 'doc-icon';
      icon.textContent = '📄';

      const info = document.createElement('div');
      info.className = 'doc-info';
      const name = document.createElement('span');
      name.className = 'doc-name';
      name.textContent = d.name;
      name.title = d.name;
      const meta = document.createElement('span');
      meta.className = 'doc-meta';
      meta.textContent = d.chunkCount + ' 块';
      info.append(name, meta);

      const del = document.createElement('button');
      del.type = 'button';
      del.className = 'doc-del';
      del.textContent = '删除';
      del.title = '删除《' + d.name + '》';
      del.addEventListener('click', () => deleteDoc(d.name));

      li.append(icon, info, del);
      docList.appendChild(li);
    }
  }

  async function deleteDoc(name) {
    if (!confirm('确定要删除《' + name + '》吗？其所有分块将一并从知识库移除。')) return;
    try {
      const res = await fetch('/rag/docs?name=' + encodeURIComponent(name), { method: 'DELETE' });
      const body = await res.json().catch(() => null);
      if (!res.ok) throw new Error((body && body.message) || 'HTTP ' + res.status);
      showToast((body && body.message) || '《' + name + '》已删除');
      await loadDocs();
    } catch (err) {
      showToast('删除失败：' + err.message.slice(0, 80));
    }
  }

  // ============================================================
  // Toast
  // ============================================================
  let toastTimer = null;
  function showToast(msg) {
    toastEl.textContent = msg;
    toastEl.hidden = false;
    requestAnimationFrame(() => toastEl.classList.add('show'));
    if (toastTimer) clearTimeout(toastTimer);
    toastTimer = setTimeout(() => {
      toastEl.classList.remove('show');
      toastEl.hidden = true;
    }, 3500);
  }

  // ============================================================
  // 输入框
  // ============================================================
  function autoResize() {
    chatInput.style.height = 'auto';
    chatInput.style.height = Math.min(chatInput.scrollHeight, 160) + 'px';
  }

  // ============================================================
  // 事件绑定
  // ============================================================
  sendBtn.addEventListener('click', () => {
    if (isStreaming) {
      if (abortController) abortController.abort();
      return;
    }
    send();
  });

  chatInput.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && !e.shiftKey && !isStreaming) {
      e.preventDefault();
      send();
    }
  });

  chatInput.addEventListener('input', autoResize);

  newChatBtn.addEventListener('click', () => {
    if (isStreaming && abortController) abortController.abort();
    history = [];
    messagesEl.innerHTML = '';
    renderWelcome();
  });

  // 上传：点击 / 键盘 / 拖拽
  dropZone.addEventListener('click', () => fileInput.click());
  dropZone.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); fileInput.click(); }
  });

  ['dragenter', 'dragover'].forEach((ev) =>
    dropZone.addEventListener(ev, (e) => {
      e.preventDefault();
      dropZone.classList.add('dragging');
    }));
  ['dragleave', 'drop'].forEach((ev) =>
    dropZone.addEventListener(ev, (e) => {
      e.preventDefault();
      dropZone.classList.remove('dragging');
    }));
  dropZone.addEventListener('drop', (e) => {
    const file = e.dataTransfer && e.dataTransfer.files && e.dataTransfer.files[0];
    if (file) uploadFile(file);
  });

  fileInput.addEventListener('change', () => {
    if (fileInput.files && fileInput.files[0]) uploadFile(fileInput.files[0]);
    fileInput.value = '';
  });

  // 代码块复制按钮（事件委托）
  messagesEl.addEventListener('click', async (e) => {
    const btn = e.target.closest('.copy-btn');
    if (!btn) return;
    const code = decodeURIComponent(btn.dataset.code || '');
    const old = btn.textContent;
    try {
      await navigator.clipboard.writeText(code);
      btn.textContent = '已复制';
    } catch {
      btn.textContent = '复制失败';
    }
    setTimeout(() => { btn.textContent = old; }, 1500);
  });

  // ============================================================
  // 初始化
  // ============================================================
  function renderWelcome() {
    addMessage('assistant',
      '你好，我是知识库助手 👋\n\n'
      + '先在上方**上传 PDF / Word 文档**，然后就可以向我提问了。\n\n'
      + '支持**多轮对话**——你可以连续追问，我会结合文档内容回答。\n'
      + '发送：Enter　·　换行：Shift+Enter　·　停止生成：点击「停止」');
  }

  renderWelcome();
  chatInput.focus();
  loadDocs();   // 启动时加载文档列表
})();
