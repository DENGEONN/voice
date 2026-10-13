const { app, BrowserWindow, ipcMain, session, shell, screen, desktopCapturer, Tray, Menu, Notification, nativeImage } = require('electron');
const path = require('path');
const fs = require('fs');
const { checkAndStage, cmpVer } = require('./updater');
const { Watcher } = require('./watcher');

const VERSION = global.__dvVersion || '0.0.0';
const BUNDLED_DIR = global.__dvBundledDir || __dirname;

// Откуда качаем обновления (папка dvoice в репозитории DENGEONN/voice, GitHub Pages и запасной raw)
const UPDATE_BASES = [
  'https://dengeonn.github.io/voice/dvoice',
  'https://raw.githubusercontent.com/DENGEONN/voice/main/dvoice'
];

app.setAppUserModelId('local.dvoice.chat');

function assetPath(name) {
  const p = path.join(__dirname, name);
  return fs.existsSync(p) ? p : path.join(BUNDLED_DIR, name);
}

// ---------- глобальный перехват клавиш/мыши ----------
let uio = null, Key = {};
for (const tryLoad of [
  () => require('uiohook-napi'),
  () => require(path.join(BUNDLED_DIR, 'node_modules', 'uiohook-napi'))
]) {
  try { ({ uIOhook: uio, UiohookKey: Key } = tryLoad()); break; } catch (e) { /* пробуем дальше */ }
}
if (!uio) console.error('uiohook-napi не загрузился, глобальные клавиши недоступны');

const keyNames = {};
for (const [name, code] of Object.entries(Key)) if (!(code in keyNames)) keyNames[code] = name;

// ---------- настройки ----------
const cfgPath = () => path.join(app.getPath('userData'), 'config.json');
function loadCfg() { try { return JSON.parse(fs.readFileSync(cfgPath(), 'utf8')); } catch (e) { return {}; } }
function saveCfg(patch) {
  try { fs.writeFileSync(cfgPath(), JSON.stringify({ ...loadCfg(), ...patch })); } catch (e) {}
}

const cfg0 = loadCfg();
const binds = { ptt: cfg0.ptt || { type: 'key', code: Key.F9 }, mute: cfg0.mute || null };
const down = { ptt: false, mute: false };
let capturing = null;   // { target, resolve }
let win = null, overlay = null, tray = null;
let quitting = false;
const startHidden = process.argv.includes('--hidden');
const prefs = { closeToTray: true, autostart: false, trayHinted: false, ...(cfg0.prefs || {}) };
let watchCfg = { enabled: true, name: '', favs: [], ...(cfg0.watch || {}) };
let overlayCfg = { enabled: false, corner: 'tr', ...(cfg0.overlay || {}) };
let lastOverlayList = [];

function labelOf(b) {
  if (!b) return 'не назначена';
  if (b.type === 'mouse') return 'Кнопка мыши ' + b.button;
  return keyNames[b.code] || ('код ' + b.code);
}
function matches(b, type, v) {
  return !!b && b.type === type && (type === 'key' ? b.code === v : b.button === v);
}
function send(ch, ...a) {
  if (win && !win.isDestroyed()) win.webContents.send(ch, ...a);
}

function finishCapture(b) {
  const c = capturing;
  capturing = null;
  if (!c) return;
  if (b) {
    const other = c.target === 'ptt' ? 'mute' : 'ptt';
    if (binds[other] && b.type === binds[other].type && (b.code === binds[other].code && b.button === binds[other].button)) {
      if (other === 'ptt') { c.resolve(null); return; }   // PTT нельзя отобрать
      binds[other] = null;
    }
    binds[c.target] = b;
    down.ptt = false; down.mute = false;
    saveCfg({ ptt: binds.ptt, mute: binds.mute });
  }
  c.resolve(b ? labelOf(b) : null);
}

function handleDown(type, v, isEsc) {
  if (capturing) {
    if (isEsc) return finishCapture(null);
    if (type === 'mouse' && v < 3) return;     // левую/правую кнопку не берём
    return finishCapture(type === 'key' ? { type, code: v } : { type, button: v });
  }
  if (matches(binds.ptt, type, v) && !down.ptt) { down.ptt = true; send('ptt:state', true); }
  if (matches(binds.mute, type, v) && !down.mute) { down.mute = true; send('mute:toggle'); }
}
function handleUp(type, v) {
  if (matches(binds.ptt, type, v) && down.ptt) { down.ptt = false; send('ptt:state', false); }
  if (matches(binds.mute, type, v) && down.mute) { down.mute = false; }
}

if (uio) {
  uio.on('keydown', e => handleDown('key', e.keycode, e.keycode === Key.Escape));
  uio.on('keyup', e => handleUp('key', e.keycode));
  uio.on('mousedown', e => handleDown('mouse', e.button, false));
  uio.on('mouseup', e => handleUp('mouse', e.button));
}

ipcMain.handle('bind:get', () => ({ ptt: labelOf(binds.ptt), mute: labelOf(binds.mute), hook: !!uio }));
ipcMain.handle('bind:capture', (_e, target) => new Promise(resolve => {
  if (!uio || (target !== 'ptt' && target !== 'mute')) return resolve(null);
  if (capturing) capturing.resolve(null);
  capturing = { target, resolve };
}));
ipcMain.handle('bind:clear', (_e, target) => {
  if (target === 'mute') { binds.mute = null; saveCfg({ mute: null }); }
  return labelOf(binds.mute);
});

// Свои ICE-серверы (TURN): файл ice.json рядом с DVoice.exe
ipcMain.handle('ice:get', () => {
  for (const d of [path.dirname(process.execPath), app.getPath('userData')]) {
    try {
      const j = JSON.parse(fs.readFileSync(path.join(d, 'ice.json'), 'utf8'));
      if (Array.isArray(j) && j.length) return j;
    } catch (e) {}
  }
  return null;
});

ipcMain.handle('app:info', () => ({ version: VERSION, name: 'DVoice' }));

// ---------- избранные каналы: уведомления о входе ----------
let peerOpts = {};
try { if (process.env.DV_PEER_JSON) peerOpts = JSON.parse(process.env.DV_PEER_JSON); } catch (e) {}

function showWindow() {
  if (!win || win.isDestroyed()) { createWindow(); return; }
  if (win.isMinimized()) win.restore();
  win.show();
  win.focus();
}

function notifyJoin(j) {
  console.log('[DVoice] уведомление о входе:', j.label, '/', j.name);
  try {
    if (!Notification.isSupported()) return;
    const n = new Notification({
      title: 'DVoice',
      body: 'В канал «' + j.label + '» присоединился ' + j.name,
      icon: nativeImage.createFromPath(assetPath('icon.png')),
      silent: false
    });
    n.on('click', () => { showWindow(); send('room:open', j.room); });
    n.show();
  } catch (e) { console.error('notify failed', e); }
}

const watcher = new Watcher({ peer: peerOpts, onJoin: notifyJoin });

function applyWatch() {
  watcher.configure({ enabled: watchCfg.enabled, name: watchCfg.name, favs: watchCfg.favs });
}

ipcMain.handle('watch:set', (_e, v) => {
  if (!v || typeof v !== 'object') return false;
  const favs = Array.isArray(v.favs) ? v.favs.slice(0, 20).map(f => ({
    room: String((f && f.room) || '').slice(0, 40),
    label: String((f && f.label) || '').slice(0, 40),
    notify: !(f && f.notify === false)
  })) : [];
  watchCfg = { enabled: v.enabled !== false, name: String(v.name || '').slice(0, 40), favs };
  saveCfg({ watch: watchCfg });
  applyWatch();
  return true;
});
ipcMain.on('watch:inRoom', (_e, room) => watcher.setInRoom(typeof room === 'string' ? room : null));

function applyAutostart() {
  try {
    app.setLoginItemSettings({ openAtLogin: !!prefs.autostart, path: process.execPath, args: ['--hidden'] });
  } catch (e) {}
}
ipcMain.handle('prefs:get', () => ({ closeToTray: prefs.closeToTray, autostart: prefs.autostart, tray: !!tray }));
ipcMain.handle('prefs:set', (_e, v) => {
  if (v && typeof v.closeToTray === 'boolean') prefs.closeToTray = v.closeToTray;
  if (v && typeof v.autostart === 'boolean') { prefs.autostart = v.autostart; applyAutostart(); }
  saveCfg({ prefs });
  return { closeToTray: prefs.closeToTray, autostart: prefs.autostart, tray: !!tray };
});

function createTray() {
  try {
    let img = nativeImage.createFromPath(assetPath('icon.png'));
    if (img.isEmpty()) return;
    img = img.resize({ width: 16, height: 16 });
    tray = new Tray(img);
    tray.setToolTip('DVoice');
    tray.setContextMenu(Menu.buildFromTemplate([
      { label: 'Открыть DVoice', click: showWindow },
      { type: 'separator' },
      { label: 'Выход', click: () => { quitting = true; app.quit(); } }
    ]));
    tray.on('click', showWindow);
  } catch (e) {
    console.error('tray failed', e);
    tray = null;
  }
}

// ---------- оверлей ----------
const OV_W = 300, OV_H = 320, OV_MARGIN = 14;

function overlayBounds() {
  const wa = screen.getPrimaryDisplay().workArea;
  const right = overlayCfg.corner === 'tr' || overlayCfg.corner === 'br';
  const bottom = overlayCfg.corner === 'bl' || overlayCfg.corner === 'br';
  return {
    width: OV_W, height: OV_H,
    x: right ? wa.x + wa.width - OV_W - OV_MARGIN : wa.x + OV_MARGIN,
    y: bottom ? wa.y + wa.height - OV_H - OV_MARGIN : wa.y + OV_MARGIN
  };
}
function pushOverlay() {
  if (overlay && !overlay.isDestroyed()) overlay.webContents.send('overlay:data', { list: lastOverlayList, corner: overlayCfg.corner });
}
function createOverlay() {
  if (overlay && !overlay.isDestroyed()) return;
  overlay = new BrowserWindow({
    ...overlayBounds(),
    transparent: true, frame: false, resizable: false, movable: false, focusable: false,
    skipTaskbar: true, alwaysOnTop: true, hasShadow: false, show: false, fullscreenable: false,
    webPreferences: { preload: assetPath('overlay-preload.js'), contextIsolation: true, nodeIntegration: false, backgroundThrottling: false }
  });
  overlay.setAlwaysOnTop(true, 'screen-saver');
  overlay.setIgnoreMouseEvents(true);
  overlay.loadFile(assetPath('overlay.html'));
  overlay.webContents.on('did-finish-load', pushOverlay);
  overlay.once('ready-to-show', () => { if (overlay && !overlay.isDestroyed()) overlay.showInactive(); });
  overlay.on('closed', () => { overlay = null; });
}
function destroyOverlay() {
  if (overlay && !overlay.isDestroyed()) overlay.destroy();
  overlay = null;
}
function applyOverlay() {
  if (overlayCfg.enabled) {
    createOverlay();
    if (overlay && !overlay.isDestroyed()) { overlay.setBounds(overlayBounds()); pushOverlay(); }
  } else destroyOverlay();
}

ipcMain.handle('overlay:get', () => overlayCfg);
ipcMain.handle('overlay:set', (_e, v) => {
  if (v && typeof v === 'object') {
    if (typeof v.enabled === 'boolean') overlayCfg.enabled = v.enabled;
    if (['tl', 'tr', 'bl', 'br'].includes(v.corner)) overlayCfg.corner = v.corner;
    saveCfg({ overlay: overlayCfg });
    applyOverlay();
  }
  return overlayCfg;
});
ipcMain.on('overlay:update', (_e, list) => {
  if (!Array.isArray(list)) return;
  lastOverlayList = list.slice(0, 8).map(x => ({
    name: String((x && x.name) || '').slice(0, 40),
    speaking: !!(x && x.speaking),
    me: !!(x && x.me),
    muted: !!(x && x.muted)
  }));
  pushOverlay();
});

// ---------- показ экрана ----------
let pendingSource = null;   // { id, audio }: выбор пользователя из нашего окна выбора

ipcMain.handle('screen:sources', async () => {
  const srcs = await desktopCapturer.getSources({
    types: ['screen', 'window'],
    thumbnailSize: { width: 320, height: 180 },
    fetchWindowIcons: false
  });
  return srcs
    .filter(s => !/^DVoice/.test(s.name))
    .map(s => ({ id: s.id, name: s.name, thumb: s.thumbnail.toDataURL() }));
});
ipcMain.handle('screen:choose', (_e, v) => {
  pendingSource = (v && typeof v.id === 'string') ? { id: v.id, audio: !!v.audio } : null;
  return true;
});

// ---------- автообновление ----------
let stagedVersion = null;
let checking = false;

async function runCheck() {
  if (checking) return { status: 'busy' };
  checking = true;
  try {
    const base = stagedVersion && cmpVer(stagedVersion, VERSION) > 0 ? stagedVersion : VERSION;
    const r = await checkAndStage({
      baseUrls: UPDATE_BASES,
      currentVersion: base,
      updatesDir: (() => { const d = path.join(app.getPath('userData'), 'updates'); fs.mkdirSync(d, { recursive: true }); return d; })()
    });
    if (r.status === 'staged') stagedVersion = r.version;
    send('update:status', r);
    return r;
  } catch (e) {
    const r = { status: 'error', message: String(e && e.message || e) };
    send('update:status', r);
    return r;
  } finally { checking = false; }
}
ipcMain.handle('update:check', () => runCheck());
ipcMain.handle('update:apply', () => { app.relaunch(); app.exit(0); });

// ---------- окно ----------
function createWindow() {
  win = new BrowserWindow({
    width: 520,
    height: 860,
    minWidth: 400,
    minHeight: 560,
    backgroundColor: '#11151c',
    show: !startHidden,
    autoHideMenuBar: true,
    title: 'DVoice',
    icon: assetPath('icon.png'),
    webPreferences: {
      preload: assetPath('preload.js'),
      contextIsolation: true,
      nodeIntegration: false,
      backgroundThrottling: false
    }
  });
  win.removeMenu();
  win.loadFile(assetPath('index.html'));
  win.webContents.setWindowOpenHandler(({ url }) => {
    if (/^https:\/\//.test(url)) shell.openExternal(url);
    return { action: 'deny' };
  });
  win.webContents.on('will-navigate', e => e.preventDefault());
  win.webContents.on('did-finish-load', () => {
    setTimeout(() => { try { global.__dvConfirm && global.__dvConfirm(); } catch (e) {} }, 8000);
  });
  win.on('close', e => {
    if (!quitting && prefs.closeToTray && tray) {
      e.preventDefault();
      win.hide();
      if (!prefs.trayHinted) {
        prefs.trayHinted = true;
        saveCfg({ prefs });
        try {
          if (Notification.isSupported()) new Notification({ title: 'DVoice', body: 'Приложение свёрнуто в трей: оно продолжает работать. Выход: правой кнопкой по значку.' }).show();
        } catch (err) {}
      }
    }
  });
  win.on('closed', () => { win = null; destroyOverlay(); });
}

const gotLock = app.requestSingleInstanceLock();
if (!gotLock) {
  app.quit();
} else {
  app.on('second-instance', () => showWindow());
  app.on('before-quit', () => { quitting = true; });
  app.whenReady().then(() => {
    const okPerm = p => p === 'media' || p === 'display-capture';
    session.defaultSession.setPermissionRequestHandler((wc, permission, cb) => cb(okPerm(permission)));
    session.defaultSession.setPermissionCheckHandler((wc, permission) => okPerm(permission));
    session.defaultSession.setDisplayMediaRequestHandler(async (request, callback) => {
      try {
        const pick = pendingSource;
        pendingSource = null;
        if (!pick) return callback({});          // без выбора в нашем окне ничего не отдаём
        const srcs = await desktopCapturer.getSources({ types: ['screen', 'window'] });
        const src = srcs.find(s => s.id === pick.id);
        if (!src) return callback({});
        callback(pick.audio ? { video: src, audio: 'loopback' } : { video: src });
      } catch (e) { callback({}); }
    });
    createWindow();
    createTray();
    applyAutostart();
    applyWatch();
    if (uio) { try { uio.start(); } catch (e) { console.error(e); } }
    if (overlayCfg.enabled) applyOverlay();
    if (!process.env.DV_NO_UPDATE) {
      setTimeout(runCheck, 4000);
      setInterval(runCheck, 20 * 60 * 1000);
    }
  });
  app.on('window-all-closed', () => app.quit());
  app.on('will-quit', () => { watcher.stopAll(); if (uio) { try { uio.stop(); } catch (e) {} } });
}
