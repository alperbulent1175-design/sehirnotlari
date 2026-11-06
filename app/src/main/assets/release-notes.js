// Release notes come from the network. They are parsed into plain data and rendered with
// createElement/textContent only: no HTML from the notes is ever interpreted and links are
// shown as text, because following them would navigate the app's WebView away.
(function (root) {
  'use strict';

  var MAX_CHARS = 20000;
  var MAX_BLOCKS = 300;
  var INLINE = /(`[^`\n]+`)|(\*\*[^*\n]+\*\*)|(__[^_\n]+__)|(\[[^\]\n]+\]\([^)\s]+\))|(\*[^*\s][^*\n]*\*)/g;

  function inline(text) {
    var spans = [];
    var last = 0;
    var m;
    INLINE.lastIndex = 0;
    while ((m = INLINE.exec(text)) !== null) {
      if (m.index > last) spans.push({ t: 'text', v: text.slice(last, m.index) });
      var s = m[0];
      if (m[1]) spans.push({ t: 'code', v: s.slice(1, -1) });
      else if (m[2] || m[3]) spans.push({ t: 'strong', v: s.slice(2, -2) });
      else if (m[4]) {
        var cut = s.indexOf('](');
        var label = s.slice(1, cut);
        var url = s.slice(cut + 2, -1);
        spans.push({ t: 'text', v: label });
        if (/^https:\/\//i.test(url) && url !== label) spans.push({ t: 'url', v: ' (' + url + ')' });
      } else spans.push({ t: 'em', v: s.slice(1, -1) });
      last = m.index + s.length;
    }
    if (last < text.length) spans.push({ t: 'text', v: text.slice(last) });
    return spans;
  }

  function stripHtml(text) {
    return text.replace(/<!--[\s\S]*?-->/g, '').replace(/<\/?[A-Za-z][^>]*>/g, '');
  }

  function parse(md) {
    var source = typeof md === 'string' ? md : '';
    var truncated = source.length > MAX_CHARS;
    if (truncated) source = source.slice(0, MAX_CHARS);
    source = stripHtml(source.replace(/\r\n?/g, '\n'));
    var lines = source.split('\n');
    var blocks = [];
    var para = null;
    var list = null;
    var fence = null;

    // GitHub keeps single line breaks in release notes; the dialog shows them with white-space:pre-line.
    function flush() {
      if (para) blocks.push({ type: 'p', spans: inline(para.join('\n')) });
      if (list) blocks.push(list);
      para = null;
      list = null;
    }

    for (var i = 0; i < lines.length && blocks.length < MAX_BLOCKS; i++) {
      var line = lines[i];
      if (fence) {
        if (/^\s*```/.test(line)) { blocks.push({ type: 'pre', text: fence.join('\n') }); fence = null; }
        else fence.push(line);
        continue;
      }
      if (/^\s*```/.test(line)) { flush(); fence = []; continue; }
      var trimmed = line.trim();
      if (!trimmed) { flush(); continue; }
      var m;
      if ((m = /^(#{1,6})\s+(.*)$/.exec(trimmed))) {
        flush();
        var text = m[2].replace(/\s+#+\s*$/, '');
        if (text) blocks.push({ type: 'h', level: m[1].length <= 2 ? 1 : 2, spans: inline(text) });
        continue;
      }
      if (/^(-{3,}|\*{3,}|_{3,})$/.test(trimmed.replace(/\s/g, ''))) { flush(); blocks.push({ type: 'hr' }); continue; }
      if ((m = /^[-*+]\s+(.*)$/.exec(trimmed)) || (m = /^\d{1,3}[.)]\s+(.*)$/.exec(trimmed))) {
        var ordered = !/^[-*+]/.test(trimmed);
        var item = m[1].replace(/^\[[ xX]\]\s+/, '');
        if (para) { blocks.push({ type: 'p', spans: inline(para.join('\n')) }); para = null; }
        if (!list || list.ordered !== ordered) {
          if (list) blocks.push(list);
          list = { type: 'list', ordered: ordered, items: [] };
        }
        list.items.push(inline(item));
        continue;
      }
      if ((m = /^>\s?(.*)$/.exec(trimmed))) {
        flush();
        if (m[1]) blocks.push({ type: 'quote', spans: inline(m[1]) });
        continue;
      }
      if (list) { list.items[list.items.length - 1] = list.items[list.items.length - 1].concat([{ t: 'text', v: ' ' }], inline(trimmed)); continue; }
      if (!para) para = [];
      para.push(trimmed);
    }
    if (fence && fence.length) blocks.push({ type: 'pre', text: fence.join('\n') });
    flush();
    if (blocks.length >= MAX_BLOCKS) truncated = true;
    return { blocks: blocks, truncated: truncated, empty: blocks.length === 0 };
  }

  function appendSpans(doc, parent, spans) {
    for (var i = 0; i < spans.length; i++) {
      var s = spans[i];
      var node;
      if (s.t === 'strong') node = doc.createElement('strong');
      else if (s.t === 'em') node = doc.createElement('em');
      else if (s.t === 'code') node = doc.createElement('code');
      else if (s.t === 'url') { node = doc.createElement('span'); node.className = 'rn-url'; }
      else { parent.appendChild(doc.createTextNode(s.v)); continue; }
      node.textContent = s.v;
      parent.appendChild(node);
    }
  }

  function render(doc, container, md) {
    while (container.firstChild) container.removeChild(container.firstChild);
    var parsed = parse(md);
    if (parsed.empty) {
      var none = doc.createElement('p');
      none.className = 'rn-empty';
      none.textContent = 'Bu sürüm için not eklenmemiş.';
      container.appendChild(none);
      return parsed;
    }
    parsed.blocks.forEach(function (b) {
      var el;
      if (b.type === 'h') { el = doc.createElement(b.level === 1 ? 'h3' : 'h4'); appendSpans(doc, el, b.spans); }
      else if (b.type === 'p') { el = doc.createElement('p'); appendSpans(doc, el, b.spans); }
      else if (b.type === 'quote') { el = doc.createElement('blockquote'); appendSpans(doc, el, b.spans); }
      else if (b.type === 'pre') { el = doc.createElement('pre'); el.textContent = b.text; }
      else if (b.type === 'hr') { el = doc.createElement('hr'); }
      else {
        el = doc.createElement(b.ordered ? 'ol' : 'ul');
        b.items.forEach(function (spans) {
          var li = doc.createElement('li');
          appendSpans(doc, li, spans);
          el.appendChild(li);
        });
      }
      container.appendChild(el);
    });
    if (parsed.truncated) {
      var more = doc.createElement('p');
      more.className = 'rn-empty';
      more.textContent = 'Notların devamı GitHub sürüm sayfasındadır.';
      container.appendChild(more);
    }
    return parsed;
  }

  root.ReleaseNotes = { parse: parse, render: render, MAX_CHARS: MAX_CHARS };
})(this);
