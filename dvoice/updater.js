// Автообновление: качает новые файлы приложения с GitHub Pages, проверяет sha256
// и кладёт в userData/updates/<версия>. boot.js подхватывает их при следующем запуске.
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const SHELL = 1;                         // версия "оболочки" (exe + boot.js); поднимается, только если нужен новый exe
const NAME_RE = /^[A-Za-z0-9._-]{1,64}$/;
const ALLOWED_EXT = new Set(['.js', '.html', '.png', '.json', '.css']);
const MAX_FILE = 8 * 1024 * 1024;

function cmpVer(a, b) {
  const pa = String(a).split('.').map(Number), pb = String(b).split('.').map(Number);
  for (let i = 0; i < 3; i++) {
    const d = (pa[i] || 0) - (pb[i] || 0);
    if (d) return d > 0 ? 1 : -1;
  }
  return 0;
}

async function getBuf(url, limit) {
  const ctl = new AbortController();
  const t = setTimeout(() => ctl.abort(), 20000);
  try {
    const r = await fetch(url, { signal: ctl.signal, cache: 'no-store' });
    if (!r.ok) throw new Error('HTTP ' + r.status + ' ' + url);
    const buf = Buffer.from(await r.arrayBuffer());
    if (buf.length > limit) throw new Error('файл слишком большой: ' + url);
    return buf;
  } finally { clearTimeout(t); }
}

function validate(man) {
  if (!man || typeof man !== 'object') throw new Error('bad manifest');
  if (!/^\d+\.\d+\.\d+$/.test(man.version || '')) throw new Error('bad version');
  if (!man.files || typeof man.files !== 'object') throw new Error('no files');
  const names = Object.keys(man.files);
  if (!names.includes('main.js') || !names.includes('index.html')) throw new Error('manifest incomplete');
  for (const n of names) {
    if (!NAME_RE.test(n) || !ALLOWED_EXT.has(path.extname(n).toLowerCase())) throw new Error('bad file name: ' + n);
    if (!/^[a-f0-9]{64}$/.test(String(man.files[n]))) throw new Error('bad hash for ' + n);
  }
}

// Возвращает { status: 'uptodate'|'staged'|'needs-new-exe'|'error', version?, message? }
async function checkAndStage({ baseUrls, currentVersion, updatesDir }) {
  let man = null, base = null, lastErr = null;
  for (const b of baseUrls) {
    try {
      man = JSON.parse((await getBuf(b + '/version.json?t=' + Date.now(), 1024 * 256)).toString('utf8'));
      validate(man);
      base = b;
      break;
    } catch (e) { lastErr = e; man = null; }
  }
  if (!man) return { status: 'error', message: String(lastErr && lastErr.message || lastErr) };

  if (man.minShell && man.minShell > SHELL) {
    return { status: 'needs-new-exe', version: man.version, message: 'Нужна новая сборка DVoice.exe (скачай архив заново)' };
  }
  if (cmpVer(man.version, currentVersion) <= 0) return { status: 'uptodate', version: currentVersion };

  // эта версия уже ломалась при запуске — не ставим её снова
  try {
    const cur = JSON.parse(fs.readFileSync(path.join(updatesDir, 'current.json'), 'utf8'));
    if (cur.bad && cur.version === man.version) return { status: 'uptodate', version: currentVersion };
  } catch (e) {}

  const finalDir = path.join(updatesDir, man.version);
  const tmpDir = finalDir + '.tmp';
  fs.rmSync(tmpDir, { recursive: true, force: true });
  fs.mkdirSync(tmpDir, { recursive: true });
  try {
    for (const [name, hash] of Object.entries(man.files)) {
      const buf = await getBuf(base + '/' + name, MAX_FILE);
      const got = crypto.createHash('sha256').update(buf).digest('hex');
      if (got !== hash) throw new Error('хэш не совпал: ' + name);
      fs.writeFileSync(path.join(tmpDir, name), buf);
    }
    fs.rmSync(finalDir, { recursive: true, force: true });
    fs.renameSync(tmpDir, finalDir);
    fs.writeFileSync(path.join(updatesDir, 'current.json'),
      JSON.stringify({ version: man.version, tries: 0, confirmed: false }));
    // чистим старые версии
    for (const d of fs.readdirSync(updatesDir)) {
      if (d !== man.version && /^\d+\.\d+\.\d+(\.tmp)?$/.test(d)) fs.rmSync(path.join(updatesDir, d), { recursive: true, force: true });
    }
    return { status: 'staged', version: man.version, notes: typeof man.notes === 'string' ? man.notes.slice(0, 300) : '' };
  } catch (e) {
    fs.rmSync(tmpDir, { recursive: true, force: true });
    return { status: 'error', message: String(e && e.message || e) };
  }
}

module.exports = { checkAndStage, cmpVer, SHELL };
