#!/usr/bin/env node
// rt-client.js — PC 端远程终端客户端(server/Mac 互远程用手机方案同款服务端)
// 用法:
//   node rt-client.js <host> [options]
// 选项:
//   -p, --port <n>       端口(默认 7681)
//   -t, --token <s>      token(默认读 RT_TOKEN 环境变量)
//   -a, --app <name>     服务端 app 名:kimi|claude|tmux|shell(默认 shell)
//   -s, --session <name> tmux 会话名(app=tmux 时用,默认 phone)
// 例:
//   RT_TOKEN=CHANGE_ME_TOKEN node rt-client.js 100.64.1.2            # 远端 shell
//   RT_TOKEN=CHANGE_ME_TOKEN node rt-client.js 100.64.1.2 -a tmux -s work
// 退出:远端进程退出自动断;本地 Ctrl+\ 强制退出客户端。
const { WebSocket } = require('ws');

function parseArgs(argv) {
  const args = { port: 7681, app: 'shell', session: 'phone', token: process.env.RT_TOKEN || '' };
  const pos = [];
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '-p' || a === '--port') args.port = +argv[++i];
    else if (a === '-t' || a === '--token') args.token = argv[++i];
    else if (a === '-a' || a === '--app') args.app = argv[++i];
    else if (a === '-s' || a === '--session') args.session = argv[++i];
    else if (a === '-h' || a === '--help') { console.log('用法: node rt-client.js <host> [-p port] [-t token] [-a app] [-s session]'); process.exit(0); }
    else pos.push(a);
  }
  args.host = pos[0];
  return args;
}

const args = parseArgs(process.argv.slice(2));
if (!args.host) { console.error('缺 host。-h 看用法'); process.exit(1); }
if (!args.token) { console.error('缺 token:-t 或 RT_TOKEN 环境变量'); process.exit(1); }

const cols = process.stdout.columns || 80;
const rows = process.stdout.rows || 24;
const qs = new URLSearchParams({
  app: args.app, token: args.token,
  cols: String(cols), rows: String(rows),
});
if (args.app === 'tmux') qs.set('session', args.session);
const url = `ws://${args.host}:${args.port}/ws?${qs}`;

const ws = new WebSocket(url, { perMessageDeflate: false });

function cleanup(code) {
  if (process.stdin.isTTY) process.stdin.setRawMode(false);
  process.stdout.write('\r\n[rt-client 断开]\r\n');
  process.exit(code);
}

ws.on('open', () => {
  process.stdout.write(`[rt-client 已连接 ${args.host} app=${args.app}]\r\n`);
  if (process.stdin.isTTY) {
    process.stdin.setRawMode(true);
    process.stdin.resume();
    process.stdin.on('data', d => {
      // Ctrl+\ (0x1c) 本地强退
      if (d.length === 1 && d[0] === 0x1c) { ws.close(); cleanup(0); }
      if (ws.readyState === WebSocket.OPEN) ws.send(d);
    });
    process.stdout.on('resize', () => {
      if (ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({ type: 'resize', cols: process.stdout.columns, rows: process.stdout.rows }));
      }
    });
  }
});
ws.on('message', (data, isBinary) => {
  if (isBinary) { process.stdout.write(data); return; }
  try {
    const msg = JSON.parse(data.toString());
    if (msg.type === 'exit') cleanup(msg.code || 0);
    else if (msg.type === 'error') { console.error('\r\n[服务端错误]', msg.message); cleanup(1); }
  } catch { /* 非 JSON 文本帧忽略 */ }
});
ws.on('close', () => cleanup(0));
ws.on('error', e => { console.error('[连接失败]', e.message); process.exit(1); });
process.on('SIGINT', () => { if (ws.readyState === WebSocket.OPEN) ws.send(Buffer.from([3])); });
