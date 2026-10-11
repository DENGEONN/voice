const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('vcNative', {
  info: () => ipcRenderer.invoke('app:info'),
  getBinds: () => ipcRenderer.invoke('bind:get'),
  capture: target => ipcRenderer.invoke('bind:capture', target),
  clearBind: target => ipcRenderer.invoke('bind:clear', target),
  onPtt: cb => ipcRenderer.on('ptt:state', (_e, down) => cb(!!down)),
  onMuteToggle: cb => ipcRenderer.on('mute:toggle', () => cb()),
  getIce: () => ipcRenderer.invoke('ice:get'),
  getOverlay: () => ipcRenderer.invoke('overlay:get'),
  setOverlay: v => ipcRenderer.invoke('overlay:set', v),
  overlayUpdate: list => ipcRenderer.send('overlay:update', list),
  checkUpdate: () => ipcRenderer.invoke('update:check'),
  applyUpdate: () => ipcRenderer.invoke('update:apply'),
  onUpdate: cb => ipcRenderer.on('update:status', (_e, s) => cb(s))
});
