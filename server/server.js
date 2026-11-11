const http = require('node:http');
const WebSocket = require('ws');

const port = Number(process.env.PORT || 8080);
const server = http.createServer((req, res) => {
  if (req.url === '/health' || req.url === '/') {
    res.writeHead(200, { 'content-type': 'application/json; charset=utf-8' });
    res.end(JSON.stringify({ ok: true, service: 'mini-walkie-talkie-relay' }));
    return;
  }
  res.writeHead(404, { 'content-type': 'text/plain; charset=utf-8' });
  res.end('Not found');
});

const wss = new WebSocket.Server({ server, maxPayload: 64 * 1024 });

function send(ws, value) {
  if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(value));
}

wss.on('connection', (ws) => {
  ws.room = '';
  ws.name = 'Guest';
  ws.isAlive = true;
  ws.on('pong', () => { ws.isAlive = true; });
  ws.on('error', (error) => console.warn('WebSocket error:', error.message));

  ws.on('message', (data, isBinary) => {
    if (isBinary) {
      if (!ws.room) return;
      for (const peer of wss.clients) {
        if (peer !== ws && peer.readyState === WebSocket.OPEN && peer.room === ws.room) {
          peer.send(data, { binary: true });
        }
      }
      return;
    }

    try {
      const message = JSON.parse(data.toString());
      if (message.type === 'join') {
        const room = String(message.room || 'team').trim().slice(0, 40) || 'team';
        const name = String(message.name || 'Guest').trim().slice(0, 30) || 'Guest';
        const members = [...wss.clients].filter((peer) => peer !== ws && peer.readyState === WebSocket.OPEN && peer.room === room);
        if (members.length >= 10) {
          send(ws, { type: 'info', text: 'Room is full (maximum 10 users).' });
          ws.close(1008, 'Room is full');
          return;
        }
        ws.room = room;
        ws.name = name;
        for (const peer of members) send(peer, { type: 'info', text: `${name} joined (${members.length + 1}/10)` });
        send(ws, { type: 'info', text: `Joined ${room} (${members.length + 1}/10)` });
      } else if (message.type === 'chat' && ws.room) {
        const payload = { type: 'chat', name: ws.name, text: String(message.text || '').trim().slice(0, 500) };
        if (!payload.text) return;
        for (const peer of wss.clients) {
          if (peer !== ws && peer.readyState === WebSocket.OPEN && peer.room === ws.room) send(peer, payload);
        }
      }
    } catch (_) {
      send(ws, { type: 'info', text: 'Invalid message.' });
    }
  });

  ws.on('close', () => {
    if (!ws.room) return;
    for (const peer of wss.clients) {
      if (peer !== ws && peer.readyState === WebSocket.OPEN && peer.room === ws.room) {
        send(peer, { type: 'info', text: `${ws.name} left` });
      }
    }
  });
});

// WebSocket ping/pong heartbeats detect broken connections and keep active rooms responsive.
const heartbeat = setInterval(() => {
  for (const ws of wss.clients) {
    if (ws.isAlive === false) {
      ws.terminate();
      continue;
    }
    ws.isAlive = false;
    ws.ping();
  }
}, 25_000);

server.listen(port, '0.0.0.0', () => {
  console.log(`Mini Walkie Talkie relay listening on port ${port}`);
});

function shutdown() {
  clearInterval(heartbeat);
  for (const ws of wss.clients) ws.close(1001, 'Server restarting');
  wss.close(() => server.close(() => process.exit(0)));
  setTimeout(() => process.exit(1), 25_000).unref();
}
process.on('SIGTERM', shutdown);
process.on('SIGINT', shutdown);
