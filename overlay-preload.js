const { contextBridge, ipcRenderer } = require('electron');
contextBridge.exposeInMainWorld('ovNative', {
  onData: cb => ipcRenderer.on('overlay:data', (_e, d) => cb(d))
});
