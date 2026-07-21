// remote-terminal server: expose kimi / claude CLIs over WebSocket via PTY.
// Protocol:
//   client connects: ws://host:7681/ws?app=kimi|claude|tmux&token=<token>&cols=80&rows=24
//     app=tmux runs: tmux new-session -A -s <session>  (&session=name, default "phone")
//     so it attaches to an existing tmux session (e.g. kimi1) or creates a persistent one.
//   client -> server: binary frame  = stdin bytes
//                     text frame    = JSON {"type":"resize","cols":N,"rows":N}
//   server -> client: binary frame  = PTY stdout bytes
//                     text frame    = JSON {"type":"exit","code":N}
//   GET /health?token=<token> -> 200 "ok" (401 on bad token) for reachability checks.

const http = require('http');
const crypto = require('crypto');
const fs = require('fs');
const path = require('path');
const os = require('os');
const pty = require('node-pty');
const { execFile } = require('child_process');
const { WebSocketServer } = require('ws');

const CONFIG_PATH = path.join(__dirname, 'config.json');

function loadConfig() {
  if (!fs.existsSync(CONFIG_PATH)) {
    const cfg = {
      port: 7681,
      token: crypto.randomBytes(18).toString('hex'),
      apps: {
        kimi: [path.join(os.homedir(), '.kimi-code/bin/kimi')],
        claude: [path.join(os.homedir(), '.npm-global/bin/claude')],
      },
    };
    fs.writeFileSync(CONFIG_PATH, JSON.stringify(cfg, null, 2), { mode: 0o600 });
    console.log('created config.json with new token:', cfg.token);
    return cfg;
  }
  return JSON.parse(fs.readFileSync(CONFIG_PATH, 'utf8'));
}

const config = loadConfig();
const PORT = config.port || 7681;

function ptyEnv() {
  const env = Object.assign({}, process.env);
  const extra = [
    path.join(os.homedir(), '.npm-global/bin'),
    path.join(os.homedir(), '.kimi-code/bin'),
    '/usr/local/bin',
    '/opt/homebrew/bin',
    '/usr/bin',
    '/bin',
    '/usr/sbin',
    '/sbin',
  ];
  env.PATH = extra.join(':') + ':' + (env.PATH || '');
  env.TERM = 'xterm-256color';
  env.COLORTERM = 'truecolor';
  if (!env.LANG) env.LANG = 'en_US.UTF-8';
  return env;
}

const server = http.createServer((req, res) => {
  const url = new URL(req.url, 'http://localhost');
  if (url.searchParams.get('token') !== config.token) {
    res.writeHead(401).end('bad token');
    return;
  }
  if (url.pathname === '/health') {
    res.writeHead(200, { 'content-type': 'text/plain' }).end('ok');
    return;
  }
  // 列出活 tmux 会话(客户端「接入已有会话」用)
  if (url.pathname === '/sessions') {
    const tmuxBin = (config.apps.tmux && config.apps.tmux[0]) || 'tmux';
    require('child_process').execFile(tmuxBin,
      ['ls', '-F', '#{session_name}\t#{session_windows}\t#{?session_attached,1,0}'],
      (err, stdout) => {
        const sessions = err ? [] : stdout.trim().split('\n').filter(Boolean).map(l => {
          const [name, windows, attached] = l.split('\t');
          return { name, windows: +windows || 1, attached: attached === '1' };
        });
        res.writeHead(200, { 'content-type': 'application/json' }).end(JSON.stringify({ sessions }));
      });
    return;
  }
  // 推送文件下载(phone-push 目录里的文件)
  if (url.pathname.startsWith('/files/')) {
    const name = path.basename(decodeURIComponent(url.pathname.slice('/files/'.length)));
    const file = path.join(PUSH_DIR, name);
    if (!name || !fs.existsSync(file)) { res.writeHead(404).end('not found'); return; }
    res.writeHead(200, {
      'content-type': 'application/octet-stream',
      'content-length': fs.statSync(file).size,
      'content-disposition': `attachment; filename*=UTF-8''${encodeURIComponent(name)}`,
    });
    fs.createReadStream(file).pipe(res);
    return;
  }
  res.writeHead(404).end();
});

// ---------- 文件推送:CLI 侧把文件丢进 ~/phone-push/,广播 {type:'file'} 给所有在线客户端 ----------
const PUSH_DIR = config.pushDir || path.join(os.homedir(), 'phone-push');
fs.mkdirSync(PUSH_DIR, { recursive: true });
{
  const pending = new Map();
  fs.watch(PUSH_DIR, (_ev, fname) => {
    if (!fname || fname.startsWith('.')) return;
    clearTimeout(pending.get(fname));
    // 等写入稳定(600ms 无新事件)再广播,避免大文件拷一半就通知
    pending.set(fname, setTimeout(() => {
      pending.delete(fname);
      const file = path.join(PUSH_DIR, fname);
      fs.stat(file, (err, st) => {
        if (err || !st.isFile()) return;
        const msg = JSON.stringify({ type: 'file', name: fname, size: st.size });
        for (const c of wss.clients) if (c.readyState === c.OPEN) c.send(msg);
        console.log(`[${new Date().toISOString()}] push file: ${fname} (${st.size}B)`);
      });
    }, 600));
  });
}

const wss = new WebSocketServer({ noServer: true });
let connSeq = 0;

server.on('upgrade', (req, socket, head) => {
  const url = new URL(req.url, 'http://localhost');
  if (url.pathname !== '/ws') {
    socket.destroy();
    return;
  }
  if (url.searchParams.get('token') !== config.token) {
    socket.write('HTTP/1.1 401 Unauthorized\r\n\r\n');
    socket.destroy();
    return;
  }
  const app = url.searchParams.get('app');
  if (!config.apps || !config.apps[app]) {
    socket.write('HTTP/1.1 400 Bad Request\r\n\r\n');
    socket.destroy();
    return;
  }
  wss.handleUpgrade(req, socket, head, (ws) => {
    ws._appName = app;
    ws._cols = Math.min(Math.max(parseInt(url.searchParams.get('cols')) || 80, 10), 500);
    ws._rows = Math.min(Math.max(parseInt(url.searchParams.get('rows')) || 24, 5), 200);
    ws._sessionGiven = url.searchParams.has('session');
    ws._tmuxSession = (url.searchParams.get('session') || 'phone')
      .replace(/[^a-zA-Z0-9_-]/g, '').slice(0, 32) || 'phone';
    wss.emit('connection', ws, req);
  });
});

wss.on('connection', (ws) => {
  // 排障:每连接一个序号,输入帧逐条进日志(用户手机打字重复问题的取证手段,稳定后可移除)
  ws._connId = ++connSeq;
  console.log(`[${new Date().toISOString()}] conn#${ws._connId} open app=${ws._appName} session=${ws._tmuxSession}`);
  ws.on('close', (code) => console.log(`[${new Date().toISOString()}] conn#${ws._connId} close code=${code}`));
  let argv = config.apps[ws._appName].slice();
  const tmuxBin = (config.apps.tmux && config.apps.tmux[0]) || 'tmux';
  if (ws._appName === 'tmux') {
    argv = argv.concat(['new-session', '-A', '-s', ws._tmuxSession]);
  } else if (ws._sessionGiven) {
    // 客户端给了会话名:把应用包进 tmux 命名会话(new-session -A -s <名> <命令>),
    // 会话已存在则忽略命令直接接入——本机 tmux attach -t <名> 可同屏接力,
    // 手机断开后进程在 tmux 里继续存活。
    const cmd = argv.map(a => `'${String(a).replace(/'/g, `'\\''`)}'`).join(' ');
    argv = [tmuxBin, 'new-session', '-A', '-s', ws._tmuxSession, cmd];
  }
  // 该连接是否落在 tmux 会话里(决定"鼠标上报"和"resize 后强制重绘"两个 tmux 专属增强)
  const inTmux = ws._appName === 'tmux' || ws._sessionGiven;
  const file = argv[0];
  const args = argv.slice(1);
  console.log(`[${new Date().toISOString()}] spawn ${ws._appName}: ${file} ${args.join(' ')}`);

  let term;
  try {
    term = pty.spawn(file, args, {
      name: 'xterm-256color',
      cols: ws._cols,
      rows: ws._rows,
      cwd: os.homedir(),
      env: ptyEnv(),
    });
  } catch (err) {
    console.error('spawn failed:', err.message);
    ws.send(JSON.stringify({ type: 'error', message: 'spawn failed: ' + err.message }));
    ws.close();
    return;
  }

  // 手机滑动滚屏:tmux 会话开鼠标上报(mouse on → tmux 向终端发 DECSET 1002+SGR 1006,
  // Termux 端滚动手势随之翻译成 SGR 滚轮事件 → tmux 进 copy-mode 滚屏)。
  // 延迟 800ms 等 tmux 把会话建出来(避免 set-option 赶在 new-session 前执行而静默失败)。
  // 已知副作用:server 本机 desktop attach 同一会话时,滚轮也进 copy-mode 而非终端自带
  // scrollback——可接受(手机滚屏是刚需),在此注明。
  if (inTmux) {
    setTimeout(() => {
      execFile(tmuxBin, ['set-option', '-t', ws._tmuxSession, 'mouse', 'on'], () => {});
    }, 800);
  }

  term.onData((data) => {
    if (ws.readyState === ws.OPEN) ws.send(Buffer.from(data, 'utf8'));
  });  term.onExit(({ exitCode, signal }) => {
    console.log(`[${new Date().toISOString()}] ${ws._appName} exited code=${exitCode} signal=${signal}`);
    if (ws.readyState === ws.OPEN) {
      ws.send(JSON.stringify({ type: 'exit', code: exitCode }));
      ws.close();
    }
  });

  ws.on('message', (msg, isBinary) => {
    if (isBinary) {
      const s = msg.toString('utf8');
      console.log(`[${new Date().toISOString()}] conn#${ws._connId} in ${msg.length}B ${JSON.stringify(s.slice(0, 120))}`);
      term.write(s);
      return;
    }
    try {
      const ctrl = JSON.parse(msg.toString('utf8'));
      if (ctrl.type === 'resize' && ctrl.cols > 0 && ctrl.rows > 0) {
        term.resize(Math.min(ctrl.cols, 500), Math.min(ctrl.rows, 200));
        if (inTmux) {
          // 裂屏修复:软键盘弹起/收起动画期间尺寸抖动,tmux 按中间尺寸反复重排会在
          // pane 里留下残影(多段输入框/多条状态栏叠印)。尺寸稳定 500ms 后强制 tmux
          // 全量重绘清残影。注意 refresh-client 的 -t 要的是客户端(tty)而非会话名
          // (实测 -t <会话名> 报 can't find client),先 list-clients 再逐个刷新。
          clearTimeout(ws._refreshTimer);
          ws._refreshTimer = setTimeout(() => {
            execFile(tmuxBin, ['list-clients', '-t', ws._tmuxSession, '-F', '#{client_name}'],
              (err, stdout) => {
                if (err) return;
                for (const c of stdout.trim().split('\n').filter(Boolean)) {
                  execFile(tmuxBin, ['refresh-client', '-t', c], () => {});
                }
              });
          }, 500);
        }
      }
    } catch (_) { /* ignore malformed control frames */ }
  });

  ws.on('close', () => {
    try { term.kill(); } catch (_) {}
  });
  ws.on('error', () => {
    try { term.kill(); } catch (_) {}
  });
});

server.listen(PORT, '0.0.0.0', () => {
  console.log(`remote-terminal server listening on 0.0.0.0:${PORT}`);
});
