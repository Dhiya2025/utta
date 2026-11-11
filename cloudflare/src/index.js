import { DurableObject } from 'cloudflare:workers';

const MAX_USERS_PER_ROOM = 10;

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (url.pathname === '/' || url.pathname === '/health') {
      if (request.headers.get('Upgrade')?.toLowerCase() !== 'websocket') {
        return new Response(JSON.stringify({
          ok: true,
          service: 'mini-walkie-talkie-relay',
          websocket: 'Connect with WSS; room is selected by the room query parameter.',
        }), { headers: { 'content-type': 'application/json; charset=utf-8' } });
      }
    }

    if (request.method !== 'GET' || request.headers.get('Upgrade')?.toLowerCase() !== 'websocket') {
      return new Response('Use a WebSocket connection for the relay. GET /health checks service status.', {
        status: 426,
        headers: { 'content-type': 'text/plain; charset=utf-8' },
      });
    }

    const roomName = (url.searchParams.get('room') || 'team').trim().slice(0, 40) || 'team';
    const id = env.ROOMS.idFromName(roomName);
    return env.ROOMS.get(id).fetch(request);
  },
};

export class Room extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    this.sessions = new Map();

    // Rehydrate per-connection names after the Durable Object hibernates.
    for (const ws of this.ctx.getWebSockets()) {
      const attachment = ws.deserializeAttachment();
      this.sessions.set(ws, attachment || { name: 'Guest', room: 'team', joined: false });
    }
  }

  async fetch(request) {
    if (request.method !== 'GET' || request.headers.get('Upgrade')?.toLowerCase() !== 'websocket') {
      return new Response('Expected a WebSocket upgrade.', { status: 426 });
    }

    const connected = this.ctx.getWebSockets().filter((ws) => ws.readyState === 1);
    if (connected.length >= MAX_USERS_PER_ROOM) {
      return new Response('Room is full (maximum 10 users).', { status: 429 });
    }

    const roomName = (new URL(request.url).searchParams.get('room') || 'team').trim().slice(0, 40) || 'team';
    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair);
    const session = { name: 'Guest', room: roomName, joined: false };

    this.ctx.acceptWebSocket(server);
    server.serializeAttachment(session);
    this.sessions.set(server, session);
    return new Response(null, { status: 101, webSocket: client });
  }

  webSocketMessage(ws, message) {
    const session = this.sessions.get(ws) || ws.deserializeAttachment() || { name: 'Guest', room: 'team', joined: false };

    // Android sends microphone PCM as binary WebSocket frames; relay to room peers unchanged.
    if (typeof message !== 'string') {
      if (!session.joined) return;
      for (const peer of this.ctx.getWebSockets()) {
        if (peer !== ws && peer.readyState === 1) {
          const peerSession = this.sessions.get(peer) || peer.deserializeAttachment();
          if (peerSession?.joined) {
            try { peer.send(message); } catch (_) { }
          }
        }
      }
      return;
    }

    let payload;
    try { payload = JSON.parse(message); } catch (_) {
      this.#send(ws, { type: 'info', text: 'Invalid message.' });
      return;
    }

    if (payload.type === 'join') {
      const requestedRoom = (String(payload.room || 'team').trim().slice(0, 40) || 'team');
      if (requestedRoom !== session.room) {
        this.#send(ws, { type: 'info', text: 'Room selection did not match the connection URL.' });
        ws.close(1008, 'Room mismatch');
        return;
      }

      const name = String(payload.name || 'Guest').trim().slice(0, 30) || 'Guest';
      const joinedPeers = this.ctx.getWebSockets().filter((peer) => {
        if (peer === ws || peer.readyState !== 1) return false;
        const peerSession = this.sessions.get(peer) || peer.deserializeAttachment();
        return peerSession?.joined;
      });
      const updated = { ...session, name, joined: true };
      this.sessions.set(ws, updated);
      ws.serializeAttachment(updated);
      for (const peer of joinedPeers) this.#send(peer, { type: 'info', text: `${name} joined (${joinedPeers.length + 1}/10)` });
      this.#send(ws, { type: 'info', text: `Joined ${session.room} (${joinedPeers.length + 1}/10)` });
      return;
    }

    if (payload.type === 'chat' && session.joined) {
      const text = String(payload.text || '').trim().slice(0, 500);
      if (!text) return;
      const chat = { type: 'chat', name: session.name, text };
      for (const peer of this.ctx.getWebSockets()) {
        if (peer !== ws && peer.readyState === 1) {
          const peerSession = this.sessions.get(peer) || peer.deserializeAttachment();
          if (peerSession?.joined) this.#send(peer, chat);
        }
      }
    }
  }

  webSocketClose(ws, code, reason) {
    const session = this.sessions.get(ws) || ws.deserializeAttachment();
    this.sessions.delete(ws);
    if (session?.joined) {
      for (const peer of this.ctx.getWebSockets()) {
        if (peer !== ws && peer.readyState === 1) {
          const peerSession = this.sessions.get(peer) || peer.deserializeAttachment();
          if (peerSession?.joined) this.#send(peer, { type: 'info', text: `${session.name} left` });
        }
      }
    }
    try { ws.close(code, reason); } catch (_) { }
  }

  webSocketError(ws) {
    this.sessions.delete(ws);
  }

  #send(ws, payload) {
    if (ws.readyState === 1) {
      try { ws.send(JSON.stringify(payload)); } catch (_) { }
    }
  }
}
