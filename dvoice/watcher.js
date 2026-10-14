// Следит за входом друзей в избранные каналы БЕЗ WebRTC: просто слушает сигнальный сервер PeerJS.
// Каждый "наблюдатель" занимает свободный слот dgn-watch-v1-<код>-N. Когда кто-то заходит в комнату,
// его приложение стучится в эти слоты (peer.connect с metadata), и сервер пересылает нам OFFER с именем.
const WATCH_PREFIX = 'dgn-watch-v1-';
const MAX_SLOTS = 6;
const THROTTLE_MS = 90 * 1000;

function sanitizeRoom(v) { return String(v || '').trim().toLowerCase().replace(/[^a-z0-9_-]/g, ''); }
function token() { return Math.random().toString(36).slice(2, 12); }

class RoomWatch {
  constructor(owner, room, label) {
    this.owner = owner;
    this.room = room;
    this.label = label;
    this.slot = 1;
    this.backoff = 3000;
    this.stopped = true;
    this.ws = null;
    this.hb = null;
    this.timer = null;
    this.taken = false;
  }

  start() {
    this.stopped = false;
    this.slot = 1;
    this.backoff = 3000;
    this.connect();
  }

  stop() {
    this.stopped = true;
    clearTimeout(this.timer);
    clearInterval(this.hb);
    const ws = this.ws;
    this.ws = null;
    if (ws) { try { ws.close(); } catch (e) {} }
  }

  schedule(ms) {
    clearTimeout(this.timer);
    this.timer = setTimeout(() => this.connect(), ms);
  }

  connect() {
    if (this.stopped) return;
    const o = this.owner.opts;
    const id = WATCH_PREFIX + this.room + '-' + this.slot;
    const url = (o.secure ? 'wss' : 'ws') + '://' + o.host + ':' + o.port + o.path +
      'peerjs?key=' + encodeURIComponent(o.key) + '&id=' + encodeURIComponent(id) + '&token=' + token();
    let ws;
    try { ws = new WebSocket(url); } catch (e) { this.schedule(this.nextBackoff()); return; }
    this.ws = ws;
    this.taken = false;
    ws.onmessage = ev => { if (this.ws === ws) this.onMessage(ws, ev.data); };
    ws.onerror = () => {};
    ws.onclose = () => {
      if (this.ws !== ws) return;
      this.ws = null;
      clearInterval(this.hb);
      if (this.stopped) return;
      if (this.taken) {
        this.slot += 1;
        if (this.slot > MAX_SLOTS) { this.slot = 1; this.schedule(30000); }
        else this.schedule(200);
      } else {
        this.schedule(this.nextBackoff());
      }
    };
  }

  nextBackoff() {
    const b = this.backoff;
    this.backoff = Math.min(this.backoff * 2, 60000);
    return b;
  }

  onMessage(ws, data) {
    if (typeof data !== 'string') return;
    let m;
    try { m = JSON.parse(data); } catch (e) { return; }
    if (!m || typeof m.type !== 'string') return;
    if (this.owner.debug && m.type === 'OFFER') this.owner.debug(this.room, this.slot, m.type, 'src=' + m.src, 'keys=' + Object.keys(m.payload || {}).join(','), 'metadata=' + JSON.stringify(m.payload && m.payload.metadata));
    if (m.type === 'OPEN') {
      this.backoff = 3000;
      clearInterval(this.hb);
      this.hb = setInterval(() => { try { ws.send('{"type":"HEARTBEAT"}'); } catch (e) {} }, 5000);
    } else if (m.type === 'ID-TAKEN') {
      this.taken = true;
      try { ws.close(); } catch (e) {}
    } else if (m.type === 'OFFER') {
      const md = m.payload && m.payload.metadata;
      if (md && md.dv === 'join') this.owner.handleJoin(this.room, this.label, String(md.name || '').slice(0, 40));
    }
  }
}

class Watcher {
  constructor({ peer, onJoin } = {}) {
    this.baseOpts = Object.assign({ host: '0.peerjs.com', port: 443, path: '/', secure: true, key: 'peerjs' }, peer || {});
    this.opts = this.baseOpts;
    this.onJoin = onJoin || (() => {});
    this.rooms = new Map();   // room -> RoomWatch
    this.enabled = true;
    this.selfName = '';
    this.inRoom = null;
    this.seen = new Map();
    this.debug = (peer && peer.debug) ? (...a) => console.log('[W]', ...a) : null;
  }

  // favs: [{ room, label, notify }]
  configure({ enabled, name, favs, server }) {
    const next = server && server.host ? Object.assign({}, this.baseOpts, server) : this.baseOpts;
    if (JSON.stringify(next) !== JSON.stringify(this.opts)) { this.stopAll(); this.opts = next; }
    this.enabled = enabled !== false;
    this.selfName = String(name || '').trim();
    const want = new Map();
    if (this.enabled && Array.isArray(favs)) {
      for (const f of favs) {
        const room = sanitizeRoom(f && f.room);
        if (room.length >= 4 && f.notify !== false) want.set(room, String(f.label || room).slice(0, 40));
      }
    }
    for (const [room, rw] of this.rooms) {
      if (!want.has(room)) { rw.stop(); this.rooms.delete(room); }
    }
    for (const [room, label] of want) {
      const ex = this.rooms.get(room);
      if (ex) { ex.label = label; continue; }
      const rw = new RoomWatch(this, room, label);
      this.rooms.set(room, rw);
      rw.start();
    }
  }

  setInRoom(room) { this.inRoom = room ? sanitizeRoom(room) : null; }

  handleJoin(room, label, name) {
    if (this.debug) this.debug('handleJoin', room, name, 'enabled=' + this.enabled, 'self=' + this.selfName, 'inRoom=' + this.inRoom);
    if (!this.enabled) return;
    if (this.inRoom && room === this.inRoom) return;
    if (name && this.selfName && name === this.selfName) return;
    const key = room + '|' + name;
    const now = Date.now();
    const last = this.seen.get(key) || 0;
    if (now - last < THROTTLE_MS) return;
    this.seen.set(key, now);
    if (this.seen.size > 200) for (const [k, t] of this.seen) if (now - t > THROTTLE_MS) this.seen.delete(k);
    try { this.onJoin({ room, label, name: name || 'Кто-то' }); } catch (e) {}
  }

  stopAll() {
    for (const rw of this.rooms.values()) rw.stop();
    this.rooms.clear();
  }
}

module.exports = { Watcher, sanitizeRoom, WATCH_PREFIX, MAX_SLOTS };
