import json

from spec import LANE, validate

LANES = ("place", "biome", "city", "item", "mob")

PAGE = """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Quest viewer</title>
<style>
:root{--bg:#14161a;--fg:#e6e8eb;--mut:#8b929c;--line:#2c313a;--card:#1d2026;--place:#d9a441;--biome:#4fae6a;--city:#5b8def;--item:#c26bd6;--mob:#e0645c}
@media (prefers-color-scheme:light){:root{--bg:#f6f7f9;--fg:#1b1e23;--mut:#5d6570;--line:#d5d9df;--card:#fff}}
body{margin:0;background:var(--bg);color:var(--fg);font:14px/1.45 system-ui,sans-serif}
header{padding:12px 16px;border-bottom:1px solid var(--line)}h1{margin:0;font-size:18px}
.sum{color:var(--mut)}.ok{color:var(--biome)}.bad{color:var(--mob)}
main{display:flex;flex-wrap:wrap}#graph{flex:1 1 640px;overflow:auto;padding:8px}#side{flex:0 1 360px;padding:12px 16px;border-left:1px solid var(--line)}
svg text{fill:var(--fg);font-size:11px}.lane{fill:none;stroke:var(--line)}.lbl{fill:var(--mut)!important;font-size:12px}
.node{cursor:pointer}.node rect{stroke:var(--bg);stroke-width:2}.node.sel rect{stroke:var(--fg)}
.edge{fill:none;stroke:var(--mut);stroke-width:1.5}.edge.alt{stroke-dasharray:5 3}
pre{background:var(--card);padding:8px;overflow:auto;border-radius:6px}li.say b{color:var(--mut);font-weight:600}
</style></head><body>
<header><h1 id="t"></h1><div class="sum" id="s"></div><div id="v"></div></header>
<main><div id="graph"></div><div id="side"><p class="sum">Click a hop.</p></div></main>
<script id="spec" type="application/json">__SPEC__</script>
<script id="result" type="application/json">__RESULT__</script>
<script>
const spec=JSON.parse(document.getElementById("spec").textContent),res=JSON.parse(document.getElementById("result").textContent);
const lanes=__LANES__,laneOf=__LANEOF__,W=150,H=46,GX=190,GY=84,hops=spec.hops,byId=Object.fromEntries(hops.map(h=>[h.id,h]));
const tos=h=>(h.next||[]).map(n=>typeof n==="string"?{to:n}:n);
const esc=s=>String(s).replace(/[&<>"]/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;"}[c]));
const depth={},q=[hops[0].id];depth[hops[0].id]=0;
while(q.length){const i=q.shift();for(const n of tos(byId[i]))if(byId[n.to]&&!(n.to in depth)){depth[n.to]=depth[i]+1;q.push(n.to)}}
const pos=h=>({x:90+(depth[h.id]??0)*GX,y:24+lanes.indexOf(laneOf[h.kind])*GY});
let svg="";
lanes.forEach((l,i)=>{svg+=`<rect class="lane" x="0" y="${14+i*GY}" width="${100+(Math.max(...Object.values(depth))+1)*GX}" height="${GY-8}"/><text class="lbl" x="6" y="${24+i*GY}">${l}</text>`});
hops.forEach(h=>{const a=pos(h);tos(h).forEach((n,k)=>{const t=byId[n.to];if(!t)return;const b=pos(t),x1=a.x+W,y1=a.y+H/2,x2=b.x,y2=b.y+H/2,m=(x1+x2)/2;
svg+=`<path class="edge${n.when?" alt":""}" d="M${x1} ${y1}C${m} ${y1} ${m} ${y2} ${x2} ${y2}"/><path d="M${x2} ${y2}l-7 -4v8z" fill="var(--mut)"/>`;
if(n.when)svg+=`<text x="${m-20}" y="${(y1+y2)/2-3}">${esc(n.when.has_item||"if")}</text>`})});
hops.forEach(h=>{const p=pos(h),l=laneOf[h.kind];
svg+=`<g class="node" data-id="${h.id}"><rect x="${p.x}" y="${p.y}" width="${W}" height="${H}" rx="6" fill="var(--${l})" fill-opacity=".85"/><text x="${p.x+8}" y="${p.y+18}" style="font-weight:600">${esc(h.id)}</text><text x="${p.x+8}" y="${p.y+34}">${esc(h.kind)} · stage ${h.stage}</text></g>`});
document.getElementById("graph").innerHTML=`<svg width="${110+(Math.max(...Object.values(depth))+1)*GX}" height="${lanes.length*GY+20}">${svg}</svg>`;
document.getElementById("t").textContent=spec.name+" ("+spec.quest+")";
document.getElementById("s").textContent=spec.summary+"  generator "+spec.generatorVersion;
document.getElementById("v").innerHTML=res.errors.length?`<div class="bad">INVALID</div><ul>${res.errors.map(e=>`<li>${esc(e)}</li>`).join("")}</ul>`:`<div class="ok">VALID multihop, spans: ${Object.entries(res.span).map(([k,v])=>k+" ("+v.length+")").join(", ")}</div>`;
const steps=(l)=>"<ul>"+l.map(s=>s.step==="say"?`<li class="say"><b>${esc(s.who)} ${esc(s.head)}</b> ${esc(s.text)}</li>`:s.step==="options"?`<li>options<ul>${s.options.map(o=>`<li>${esc(o.text)}${steps(o.steps||[])}</li>`).join("")}</ul></li>`:s.step==="action"?`<li>action ${esc(s.type)} <code>${esc(JSON.stringify(s.args||{}))}</code></li>`:"<li>end</li>").join("")+"</ul>";
document.getElementById("graph").addEventListener("click",ev=>{const g=ev.target.closest(".node");if(!g)return;
document.querySelectorAll(".node.sel").forEach(n=>n.classList.remove("sel"));g.classList.add("sel");
const h=byId[g.dataset.id],{steps:st,...rest}=h,sg=spec.stages.find(x=>x.stage===h.stage);
document.getElementById("side").innerHTML=`<h2>${esc(h.title||h.id)}</h2><p class="sum">${esc(sg?sg.title+": "+sg.journal:"")}</p><pre>${esc(JSON.stringify(rest,null,2))}</pre>${st?"<h3>Dialogue</h3>"+steps(st):""}`});
</script></body></html>
"""


def page(spec):
    errors, span = validate(spec) if isinstance(spec, dict) and spec.get("hops") else (["no hops"], {})
    safe = lambda x: json.dumps(x).replace("</", "<\\/")
    return PAGE.replace("__SPEC__", safe(spec)).replace("__RESULT__", safe({"errors": errors, "span": span})).replace("__LANES__", safe(LANES)).replace("__LANEOF__", safe(LANE))
