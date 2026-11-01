// Android 禁止普通应用读取网卡列表和 /proc/stat，Node 的 os 模块会因此抛错或返回空。
'use strict';
const os = require('os');
const networkInterfaces = os.networkInterfaces;
os.networkInterfaces = function () {
  try {
    return networkInterfaces.apply(os, arguments);
  } catch (error) {
    return { lo: [{ address: '127.0.0.1', netmask: '255.0.0.0', family: 'IPv4',
      mac: '00:00:00:00:00:00', internal: true, cidr: '127.0.0.1/8' }] };
  }
};
const cpus = os.cpus;
os.cpus = function () {
  const list = cpus.apply(os, arguments);
  if (list.length > 0) return list;
  let count = 1;
  try {
    count = require('fs').readFileSync('/proc/cpuinfo', 'utf8').match(/^processor/gm).length || 1;
  } catch (error) {}
  return Array.from({ length: count }, () => ({ model: 'arm64', speed: 0,
    times: { user: 0, nice: 0, sys: 0, idle: 0, irq: 0 } }));
};
