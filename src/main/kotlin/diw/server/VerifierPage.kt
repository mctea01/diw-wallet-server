package diw.server

/** The verifier's web page: ask for a card, show the wallet QR code, then the verdict. No external resources. */
object VerifierPage {
    private const val STYLE = """
:root{--bg:#f6f7f9;--fg:#1b1f24;--muted:#5b6472;--card:#fff;--line:#d8dde3;--accent:#3f51b5;--on-accent:#fff;--ok:#1b7a3d;--bad:#b3261e;--warn:#9a5b00}
@media (prefers-color-scheme:dark){:root{--bg:#121417;--fg:#e6e8eb;--muted:#9aa3ad;--card:#1c1f24;--line:#30353c;--accent:#8c9eff;--on-accent:#10132b;--ok:#5fd38a;--bad:#ff8a80;--warn:#ffb74d}}
*{box-sizing:border-box}a{color:var(--accent)}body{margin:0;background:var(--bg);color:var(--fg);font:16px/1.55 system-ui,-apple-system,"Noto Sans TC","PingFang TC","Microsoft JhengHei",sans-serif}
main{max-width:640px;margin:0 auto;padding:24px 16px 48px}h1{font-size:1.4rem;margin:0 0 4px}p.sub{color:var(--muted);margin:0 0 20px}
section{background:var(--card);border:1px solid var(--line);border-radius:12px;padding:16px;margin-bottom:16px}
label{display:block;font-weight:600;margin:12px 0 4px}label.inline{display:flex;gap:8px;align-items:center;font-weight:400;margin:6px 0}
input[type=text],input[type=password]{width:100%;padding:10px;border:1px solid var(--line);border-radius:8px;background:var(--bg);color:var(--fg);font:inherit}
.hint{color:var(--muted);font-size:.875rem;margin:4px 0 0}
button,a.button{display:inline-block;margin-top:16px;padding:10px 18px;border:0;border-radius:8px;background:var(--accent);color:var(--on-accent);font:inherit;font-weight:600;cursor:pointer;text-decoration:none}
button.secondary,a.secondary{background:transparent;color:var(--accent);border:1px solid var(--accent)}
.qr{display:block;width:min(320px,100%);margin:12px auto;border-radius:8px}.center{text-align:center}
.tabs{display:flex;gap:8px;justify-content:center}.tabs button{margin-top:0}
.verdict{border-radius:10px;padding:12px 14px;margin-bottom:12px;font-weight:600}
.verdict.ok{background:color-mix(in srgb,var(--ok) 14%,transparent);color:var(--ok)}.verdict.bad{background:color-mix(in srgb,var(--bad) 14%,transparent);color:var(--bad)}
table{width:100%;border-collapse:collapse}td{padding:8px 4px;border-bottom:1px solid var(--line);vertical-align:top;word-break:break-all}td:first-child{color:var(--muted);width:40%}
ul{padding-left:20px;margin:8px 0}.error{color:var(--bad);margin-top:12px}[hidden]{display:none!important}
"""

    fun html(needsCode: Boolean): String = """<!doctype html>
<html lang="zh-Hant"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>網頁查驗</title><style>$STYLE</style></head><body><main>
<h1>網頁查驗</h1>
<p class="sub">請持卡人以數位皮夾掃描 QR Code 出示卡片。出示的內容只保存在伺服器記憶體中數分鐘，供這個頁面讀取，不寫入紀錄。</p>
<section id="ask">
  <label>要查驗的卡片</label>
  <label class="inline"><input type="radio" name="kind" value="twdiw" checked>數位皮夾卡片（政府數位憑證皮夾）</label>
  <label class="inline"><input type="radio" name="kind" value="moica">自然人憑證自發卡（Bonds）</label>
  <div id="twdiwFields">
    <label for="cardType">卡片類型代碼</label>
    <input type="text" id="cardType" maxlength="80" placeholder="例如 00000000_example_card">
    <p class="hint">卡片的類型名稱（VC 的 type），可向發卡機關或在發卡端的中繼資料查得。</p>
    <label for="cardName">卡片名稱（選填）</label>
    <input type="text" id="cardName" maxlength="60" placeholder="顯示在持卡人的同意畫面">
  </div>
  <label for="fields">要揭露的欄位</label>
  <input type="text" id="fields" placeholder="以逗號分隔，例如 name, birthdate">
  <p class="hint">只會顯示這些欄位；皮夾多送的欄位會被捨棄。</p>
  <label for="purpose">查驗目的</label>
  <input type="text" id="purpose" maxlength="100" value="身分查驗">
  ${if (needsCode) """<label for="code">存取碼</label><input type="password" id="code" autocomplete="off"><p class="hint">這台伺服器只供授權的查驗方使用。</p>""" else ""}
  <button id="start">建立查驗</button>
  <p class="error" id="askError" hidden></p>
</section>
<section id="wait" hidden>
  <div class="tabs"><button id="tabModa">政府數位憑證皮夾</button><button id="tabOther" class="secondary">其他皮夾</button></div>
  <img class="qr" id="qr" alt="出示用的 QR Code">
  <p class="center"><a class="button secondary" id="open">在這支手機開啟皮夾</a></p>
  <p class="center hint" id="countdown"></p>
  <p class="center"><button class="secondary" id="cancel">取消</button></p>
</section>
<section id="done" hidden>
  <div class="verdict" id="verdict"></div>
  <table id="fieldsTable"></table>
  <ul id="notes"></ul>
  <p class="hint" id="issuer"></p>
  <button id="again">再查驗一次</button>
</section>
<p class="hint"><a href="/verify/privacy">隱私說明</a></p>
</main><script src="/verify/app.js"></script></body></html>"""

    const val SCRIPT = """(function(){
'use strict';
var $ = function(id){return document.getElementById(id);};
var session = null, scheme = 'modadigitalwallet', timer = null;
function show(id){['ask','wait','done'].forEach(function(s){$(s).hidden = s !== id;});}
function kind(){return document.querySelector('input[name=kind]:checked').value;}
document.querySelectorAll('input[name=kind]').forEach(function(r){r.addEventListener('change',function(){$('twdiwFields').hidden = kind() !== 'twdiw';});});
function setScheme(s){
  scheme = s;
  $('tabModa').className = s === 'modadigitalwallet' ? '' : 'secondary';
  $('tabOther').className = s === 'openid4vp' ? '' : 'secondary';
  if (!session) return;
  $('qr').src = '/api/oidvp/sessions/' + session.sessionId + '/qr.svg?scheme=' + s + '&token=' + encodeURIComponent(session.token);
  $('open').href = s === 'openid4vp' ? session.openid4vpLink : session.deepLink;
}
$('tabModa').addEventListener('click',function(){setScheme('modadigitalwallet');});
$('tabOther').addEventListener('click',function(){setScheme('openid4vp');});
$('start').addEventListener('click',function(){
  var err = $('askError'); err.hidden = true;
  var body = {kind: kind(), fields: $('fields').value.split(/[,\s]+/).filter(Boolean), purpose: $('purpose').value};
  if (body.kind === 'twdiw') { body.cardType = $('cardType').value.trim(); body.cardName = $('cardName').value.trim(); }
  var headers = {'Content-Type':'application/json'};
  if ($('code')) headers['Authorization'] = 'Bearer ' + $('code').value;
  fetch('/api/oidvp/sessions',{method:'POST',headers:headers,body:JSON.stringify(body)}).then(function(r){
    return r.json().then(function(j){ if (!r.ok) throw new Error(j.error_description || ('HTTP ' + r.status)); return j; });
  }).then(function(j){ session = j; setScheme(scheme); show('wait'); poll(); }).catch(function(e){ err.textContent = '無法建立查驗：' + e.message; err.hidden = false; });
});
function poll(){
  clearTimeout(timer);
  if (!session) return;
  var left = Math.max(0, Math.round((session.expiresAt - Date.now()) / 1000));
  $('countdown').textContent = left > 0 ? ('請在 ' + Math.floor(left / 60) + ' 分 ' + (left % 60) + ' 秒內出示') : '請求已過期';
  fetch('/api/oidvp/sessions/' + session.sessionId + '?token=' + encodeURIComponent(session.token)).then(function(r){ return r.json(); }).then(function(j){
    if (j.state === 'done') return result(j.result);
    if (j.state === 'expired') { $('countdown').textContent = '請求已過期，請重新建立'; return; }
    timer = setTimeout(poll, 2000);
  }).catch(function(){ timer = setTimeout(poll, 4000); });
}
function result(r){
  var v = $('verdict'); v.textContent = r.summary; v.className = 'verdict ' + (r.accepted ? 'ok' : 'bad');
  var t = $('fieldsTable'); t.textContent = '';
  (r.fields || []).forEach(function(f){ var tr = document.createElement('tr'); var a = document.createElement('td'); var b = document.createElement('td'); a.textContent = f.name; b.textContent = f.value; tr.appendChild(a); tr.appendChild(b); t.appendChild(tr); });
  var n = $('notes'); n.textContent = '';
  (r.notes || []).forEach(function(s){ var li = document.createElement('li'); li.textContent = s; n.appendChild(li); });
  var who = [];
  if (r.issuerName) who.push('發卡者：' + r.issuerName);
  if (r.issuerTier) who.push('信任等級 ' + r.issuerTier);
  if (r.issuer) who.push(r.issuer);
  $('issuer').textContent = who.join('，');
  session = null; show('done');
}
$('cancel').addEventListener('click',function(){ session = null; clearTimeout(timer); show('ask'); });
$('again').addEventListener('click',function(){ show('ask'); });
})();
"""

    val PRIVACY = """<!doctype html>
<html lang="zh-Hant"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>隱私說明</title><style>$STYLE</style></head><body><main>
<h1>隱私說明</h1>
<section>
<ul>
<li>持卡人出示的欄位只保存在伺服器記憶體中，查驗請求到期後最多再保留五分鐘，供建立查驗的頁面讀取，之後刪除。</li>
<li>伺服器不寫入出示內容、欄位值或持卡人識別資料的紀錄，也不轉交第三方。</li>
<li>只顯示查驗方要求的欄位；皮夾多送的欄位不會顯示或保存。</li>
<li>為檢查卡片是否被撤銷，伺服器會向發卡者的狀態清單與數位發展部的信任清單查詢，查詢內容不含欄位值。</li>
</ul>
<p><a href="/verify">回到網頁查驗</a></p>
</section></main></body></html>"""
}
