#!/usr/bin/env python3
"""Render graph.json into a self-contained interactive HTML visualization."""

import os

ROOT = os.path.dirname(os.path.abspath(__file__))

TEMPLATE = """<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<title>parallel-in-scope core 知识图谱</title>
<style>
  :root {
    --bg:#ffffff; --sidebar:#fafafa; --fg:#222222; --border:#dddddd;
    --muted:#888888; --stats:#555555; --input-bg:#ffffff; --input-fg:#222222;
    --k-link:#1a5276; --k-see:#7d3c98; --k-mention:#7f8c8d;
  }
  body.dark {
    --bg:#14181f; --sidebar:#1b222c; --fg:#d7dde5; --border:#333d4a;
    --muted:#8b98a8; --stats:#9aa7b5; --input-bg:#232b37; --input-fg:#d7dde5;
    --k-link:#6db3f2; --k-see:#c39bd3; --k-mention:#7f8c8d;
  }
  body { margin:0; font-family:-apple-system,"Helvetica Neue",Arial,sans-serif; display:flex; height:100vh; background:var(--bg); color:var(--fg); }
  #sidebar { width:340px; min-width:340px; overflow-y:auto; border-right:1px solid var(--border); padding:12px; box-sizing:border-box; background:var(--sidebar); }
  #graph { flex:1; }
  h1 { font-size:16px; margin:4px 0 8px; }
  .ctrl { margin-bottom:10px; font-size:13px; }
  select,input[type=text] { width:100%; box-sizing:border-box; padding:4px; background:var(--input-bg); color:var(--input-fg); border:1px solid var(--border); border-radius:4px; }
  label { display:block; margin:2px 0; }
  #detail { font-size:13px; border-top:1px solid var(--border); padding-top:8px; margin-top:8px; }
  #detail h2 { font-size:14px; margin:4px 0; word-break:break-all; }
  #detail .file { color:var(--muted); font-size:11px; word-break:break-all; }
  #detail ul { margin:4px 0; padding-left:18px; }
  .k-link { color:var(--k-link); } .k-see { color:var(--k-see); } .k-mention { color:var(--k-mention); }
  #stats { font-size:12px; color:var(--stats); line-height:1.5; }
  .legend span { display:inline-block; width:10px; height:10px; border-radius:5px; margin-right:4px; }
  #theme-btn { width:100%; padding:5px; cursor:pointer; background:var(--input-bg); color:var(--input-fg); border:1px solid var(--border); border-radius:4px; font-size:13px; }
  fieldset { border:1px solid var(--border); border-radius:4px; margin:0 0 10px; padding:6px 8px; }
  legend { font-size:12px; color:var(--stats); padding:0 4px; }
</style>
</head>
<body>
<div id="sidebar">
  <h1>core 知识图谱</h1>
  <div id="stats" class="ctrl"></div>
  <fieldset><legend>视图</legend>
    <label><input type="radio" name="view" value="doc" checked> 文档指针（JavaDoc）</label>
    <label><input type="radio" name="view" value="code"> 代码依赖（加权）</label>
    <label><input type="radio" name="view" value="diff"> 对照差异</label>
  </fieldset>
  <div class="ctrl"><select id="component"></select></div>
  <fieldset id="doc-filters"><legend>文档指针过滤</legend>
    <label><input type="checkbox" id="f-link" checked> {@link} 指针</label>
    <label><input type="checkbox" id="f-see" checked> @see 指针</label>
    <label><input type="checkbox" id="f-mention" checked> 正文类名提及</label>
    <label><input type="checkbox" id="f-member"> 成员级指针（方法/字段 JavaDoc）</label>
  </fieldset>
  <fieldset><legend>布局</legend>
    <label><input type="radio" name="layout" value="force" checked> 力导向</label>
    <label><input type="radio" name="layout" value="layered"> 分层（阅读顺序，自上而下）</label>
    <label><input type="checkbox" id="f-isolated"> 显示孤立类（无指针）</label>
  </fieldset>
  <div class="ctrl"><input type="text" id="search" placeholder="搜索类名，回车定位"></div>
  <div class="ctrl"><button id="theme-btn">🌙 夜间模式</button></div>
  <div class="ctrl legend" style="font-size:12px" id="kind-legend">
    <span style="background:#4a90d9"></span>class
    <span style="background:#45b29a"></span>interface
    <span style="background:#e67e22"></span>enum
    <span style="background:#9b59b6"></span>annotation
    <span style="background:#95a5a6"></span>package
    <div style="margin-top:4px">深色边框 = 嵌套类；节点大小 = 被引次数</div>
    <div id="diff-legend" style="margin-top:4px;display:none">
      边：<font color="#2e8b57">■</font> 文档与代码一致
      <font color="#1a9ce0">■</font> 仅文档提及
      <font color="#e67e22">■</font> 仅代码依赖
    </div>
  </div>
  <div id="detail"><i>点击节点查看对外指针；点击边查看出处与细节</i></div>
</div>
<div id="graph"></div>
<script>__VIS__</script>
<script>
const DATA = __DATA__;
const THEMES = {
  light: {
    kind: {class:'#4a90d9', interface:'#45b29a', enum:'#e67e22', annotation:'#9b59b6', package:'#95a5a6'},
    edge: {link:'#1a5276', see:'#7d3c98', mention:'#b2babb'},
    diff: {both:'#2e8b57', docOnly:'#1a9ce0', codeOnly:'#e67e22'},
    font: '#222222', nestedBorder: '#111111'
  },
  dark: {
    kind: {class:'#5b9bd5', interface:'#48a9a6', enum:'#e8913d', annotation:'#a06fc0', package:'#7f8c9b'},
    edge: {link:'#7fb3e0', see:'#b07cc6', mention:'#4d5a68'},
    diff: {both:'#3fbf7f', docOnly:'#4db8ff', codeOnly:'#f0a050'},
    font: '#d7dde5', nestedBorder: '#d7dde5'
  }
};
let theme = localStorage.getItem('kg-theme') ||
  (window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light');

const allNodes = DATA.nodes.map(n => ({
  id: n.id, label: n.id.split('.').pop(), title: n.id, shape: 'dot',
  kind: n.kind, nested: n.nested, file: n.file, summary: n.summary,
  component: n.component, level: n.level
}));
const docEdges = DATA.edges.map((e,i) => ({
  id: 'd'+i, from: e.from, to: e.to, kind: e.kind, reason: e.reason || '',
  rel: e.rel || null, level: e.level, members: e.members || [], arrows: 'to'
}));
const codeEdges = (DATA.codeEdges || []).map((e,i) => ({
  id: 'c'+i, from: e.from, to: e.to, weight: e.weight, arrows: 'to'
}));
const nodeById = {}; allNodes.forEach(n => nodeById[n.id] = n);
const maxLevel = Math.max(0, ...allNodes.map(n => n.level || 0));

// component dropdown
const comps = {};
allNodes.forEach(n => { (comps[n.component] = comps[n.component] || []).push(n); });
const degree = {};
docEdges.forEach(e => { degree[e.from]=(degree[e.from]||0)+1; degree[e.to]=(degree[e.to]||0)+1; });
const compList = Object.entries(comps).sort((a,b) => b[1].length - a[1].length);
const sel = document.getElementById('component');
compList.forEach(([cid, nodes], idx) => {
  const hub = nodes.slice().sort((a,b)=>(degree[b.id]||0)-(degree[a.id]||0))[0].id;
  const opt = document.createElement('option');
  opt.value = cid;
  opt.textContent = (idx===0 ? '主图' : '子图 ' + (idx+1)) + '（' + nodes.length + ' 类，核心：' + hub + '）';
  sel.appendChild(opt);
});
const optAll = document.createElement('option');
optAll.value = '__all__'; optAll.textContent = '全部 ' + allNodes.length + ' 类';
sel.appendChild(optAll);

const container = document.getElementById('graph');
let network = null;
let shownEdgeId = null;

const view = () => document.querySelector('input[name=view]:checked').value;
const layoutMode = () => document.querySelector('input[name=layout]:checked').value;

function activeDocEdges() {
  const fl = document.getElementById('f-link').checked,
        fs = document.getElementById('f-see').checked,
        fm = document.getElementById('f-mention').checked,
        fmem = document.getElementById('f-member').checked;
  return docEdges.filter(e =>
    ((e.kind==='link'&&fl)||(e.kind==='see'&&fs)||(e.kind==='mention'&&fm)) &&
    (fmem || e.level !== 'member'));
}

function diffEdges() {
  const doc = new Map(), code = new Map();
  activeDocEdges().forEach(e => doc.set(e.from+'||'+e.to, e));
  codeEdges.forEach(e => code.set(e.from+'||'+e.to, e));
  const out = [];
  doc.forEach((e,k) => out.push({
    id:'x-d-'+k, from:e.from, to:e.to, arrows:'to', provenance: code.has(k)?'both':'docOnly',
    ref:e, dashes: !code.has(k)
  }));
  code.forEach((e,k) => { if (!doc.has(k)) out.push({
    id:'x-c-'+k, from:e.from, to:e.to, arrows:'to', provenance:'codeOnly', ref:e, dashes:false
  }); });
  return out;
}

function activeEdges() {
  const v = view();
  if (v === 'doc') return activeDocEdges().map(e => Object.assign({}, e, {provenance:'doc'}));
  if (v === 'code') return codeEdges.map(e => Object.assign({}, e, {provenance:'code'}));
  return diffEdges().map(e => Object.assign({}, e, {diffKind: e.provenance, provenance:'diff'}));
}

function edgeVisual(e) {
  const t = THEMES[theme];
  if (e.provenance === 'doc') {
    return { color: {color: t.edge[e.kind]}, dashes: e.kind !== 'link', width: e.kind === 'link' ? 1.8 : 1.2 };
  }
  if (e.provenance === 'code') {
    return { color: {color: '#8e6bbf'}, dashes: false, width: 1 + Math.min(5, Math.sqrt(e.weight)) };
  }
  return { color: {color: t.diff[e.diffKind] || '#888'}, dashes: !!e.dashes, width: 2 };
}

function render() {
  const compVal = sel.value;
  let nodes = allNodes.filter(n => compVal === '__all__' || String(n.component) === compVal);
  let edges = activeEdges();
  const allowed = new Set(nodes.map(n => n.id));
  edges = edges.filter(e => allowed.has(e.from) && allowed.has(e.to));
  if (!document.getElementById('f-isolated').checked) {
    const connected = new Set();
    edges.forEach(e => { connected.add(e.from); connected.add(e.to); });
    nodes = nodes.filter(n => connected.has(n.id));
  }
  // node size = in-degree within the active edge set
  const indeg = {};
  edges.forEach(e => { indeg[e.to] = (indeg[e.to]||0)+1; });
  const layered = layoutMode() === 'layered';
  const t = THEMES[theme];
  const vnodes = nodes.map(n => {
    const c = t.kind[n.kind] || t.kind.class;
    const v = {
      id: n.id, label: n.label, title: n.title, shape: 'dot',
      size: Math.min(30, 9 + 3 * Math.sqrt(indeg[n.id] || 0)),
      color: { background: c, border: n.nested ? t.nestedBorder : c },
      borderWidth: n.nested ? 3 : 1,
      font: { size: 13, color: t.font }
    };
    if (layered) { v.y = (maxLevel - (n.level || 0)) * 130; v.fixed = { x: false, y: true }; }
    return v;
  });
  const vedges = edges.map(e => {
    const vis = edgeVisual(e);
    return Object.assign({}, e, vis);
  });
  document.getElementById('diff-legend').style.display = view() === 'diff' ? 'block' : 'none';
  network = new vis.Network(container,
    { nodes: new vis.DataSet(vnodes), edges: new vis.DataSet(vedges) },
    { physics: layered
        ? { solver: 'barnesHut', barnesHut: { gravitationalConstant: -8000, springLength: 150 }, stabilization: {iterations: 200} }
        : { solver: 'forceAtlas2Based', forceAtlas2Based: { gravitationalConstant: -60, springLength: 120 }, stabilization: {iterations: 300} },
      interaction: { hover: true, tooltipDelay: 120 } });
  network.on('click', params => {
    if (params.nodes.length) { shownEdgeId = null; showDetail(params.nodes[0]); }
    else if (params.edges.length) {
      const id = params.edges[0];
      if (id === shownEdgeId) {
        shownEdgeId = null; network.unselectAll();
        document.getElementById('detail').innerHTML = '<i>点击节点查看对外指针；点击边查看出处与细节</i>';
      } else { shownEdgeId = id; showEdge(id); }
    }
  });
  updateStats(edges);
}

function updateStats(edges) {
  const v = view();
  const memCount = docEdges.filter(e => e.level === 'member').length;
  let s = allNodes.length + ' 个类 · ';
  if (v === 'doc') s += edges.length + ' 条文档指针（含 ' + memCount + ' 条成员级，默认隐藏）';
  else if (v === 'code') s += edges.length + ' 条代码依赖（线宽 ∝ √权重）';
  else {
    const both = edges.filter(e => e.diffKind === 'both').length;
    const docOnly = edges.filter(e => e.diffKind === 'docOnly').length;
    const codeOnly = edges.filter(e => e.diffKind === 'codeOnly').length;
    s += '一致 ' + both + ' · 仅文档 ' + docOnly + ' · 仅代码 ' + codeOnly;
  }
  s += ' · ' + compList.length + ' 个有向图';
  document.getElementById('stats').textContent = s;
}

function esc(s) { return s.replace(/&/g,'&amp;').replace(/</g,'&lt;'); }

function showEdge(id) {
  const e = network.body.data.edges.get(id);
  if (!e) return;
  let h = '<h2>' + e.from + ' → ' + e.to + '</h2>';
  if (e.provenance === 'diff') {
    const label = {both: '文档与代码一致', docOnly: '仅文档提及（概念性指针或文档超前）', codeOnly: '仅代码依赖（文档未记录的耦合）'}[e.diffKind];
    h += '<div class="file"><b>' + label + '</b></div>';
  }
  const d = e.provenance === 'doc' ? e : (e.ref && e.ref.kind ? e.ref : null);
  if (d) {
    const kindLabel = {link: '{@link} 显式引用', see: '@see 引用', mention: '正文提及'}[d.kind] || d.kind;
    const relLabel = (DATA.categories && d.rel) ? DATA.categories[d.rel] : null;
    h += '<div class="file">文档指针 · ' + kindLabel + (d.level === 'member' ? ' · 成员级' : '') +
         (relLabel ? ' · 关系：<b>' + relLabel + '</b>' : '') + '</div>';
    if (d.reason) h += '<p style="border-left:3px solid var(--border);padding-left:8px;margin:8px 0">' + esc(d.reason) + '</p>';
    if (d.members && d.members.length) h += '<div class="file">引用成员：' + d.members.join('、') + '</div>';
    h += '<div class="file">出处：' + (nodeById[e.from] ? nodeById[e.from].file : '') + ' 的 JavaDoc</div>';
  }
  const c = e.provenance === 'code' ? e : (e.ref && e.ref.weight != null ? e.ref : null);
  if (c) h += '<div class="file">代码依赖 · 标识符引用 ' + c.weight + ' 次</div>';
  h += '<div class="file">（再点一次该边可收起）</div>';
  document.getElementById('detail').innerHTML = h;
}

function showDetail(id) {
  const n = nodeById[id];
  const v = view();
  const pool = v === 'code' ? codeEdges : (v === 'doc' ? activeDocEdges() : []);
  const outs = pool.filter(e => e.from === id);
  const ins = pool.filter(e => e.to === id);
  let h = '<h2>' + id + '</h2><div class="file">' + n.file + (n.nested ? '（嵌套类）' : '') + ' · 阅读层级 ' + (maxLevel - (n.level||0)) + '</div>';
  if (n.summary) h += '<p>' + esc(n.summary) + '</p>';
  h += '<b>对外（' + outs.length + '）</b><ul>';
  outs.forEach(e => {
    const extra = e.weight != null ? ' ×' + e.weight : (e.level === 'member' ? ' <small>(成员级)</small>' : '');
    h += '<li>→ ' + e.to + extra + '</li>';
  });
  if (!outs.length) h += '<li>无</li>';
  h += '</ul><b>被引（' + ins.length + '）</b><ul>';
  ins.forEach(e => { h += '<li>← ' + e.from + '</li>'; });
  if (!ins.length) h += '<li>无</li>';
  h += '</ul>';
  document.getElementById('detail').innerHTML = h;
}

function applyTheme() {
  document.body.classList.toggle('dark', theme === 'dark');
  document.getElementById('theme-btn').textContent = theme === 'dark' ? '☀️ 日间模式' : '🌙 夜间模式';
  render();
}
document.getElementById('theme-btn').onclick = () => {
  theme = theme === 'dark' ? 'light' : 'dark';
  localStorage.setItem('kg-theme', theme);
  applyTheme();
};
sel.onchange = render;
document.querySelectorAll('input[name=view],input[name=layout]').forEach(el => el.onchange = render);
['f-link','f-see','f-mention','f-member','f-isolated'].forEach(id => document.getElementById(id).onchange = render);
document.getElementById('search').onkeydown = ev => {
  if (ev.key !== 'Enter') return;
  const q = ev.target.value.trim().toLowerCase();
  const hit = allNodes.find(n => n.id.toLowerCase().endsWith(q) || n.id.toLowerCase().includes(q));
  if (hit && network) { network.focus(hit.id, {scale: 1.4, animation: true}); network.selectNodes([hit.id]); showDetail(hit.id); }
};
applyTheme();
</script>
</body>
</html>
"""


def main():
    data = open(os.path.join(ROOT, "graph.json"), encoding="utf-8").read()
    vis_path = os.path.join(ROOT, "vis-network.min.js")
    if not os.path.exists(vis_path):
        import shutil

        shutil.copy("/tmp/vis-network.min.js", vis_path)
    vis = open(vis_path, encoding="utf-8").read()
    html = TEMPLATE.replace("__VIS__", vis).replace("__DATA__", data)
    out = os.path.join(ROOT, "index.html")
    with open(out, "w", encoding="utf-8") as f:
        f.write(html)
    print(out, os.path.getsize(out), "bytes")


if __name__ == "__main__":
    main()
