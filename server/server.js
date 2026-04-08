const http = require('http');
const fs   = require('fs');
const path = require('path');
const { WebSocketServer } = require('ws');

const PORT = process.env.PORT || 8080;

const httpServer = http.createServer((req, res) => {
    // basic path traversal guard
    const safePath = path.normalize(req.url).replace(/^(\.\.[/\\])+/, '');
    const filePath = path.join(__dirname, 'public', safePath === '/' ? 'index.html' : safePath);
    fs.readFile(filePath, (err, data) => {
        if (err) { res.writeHead(404); res.end('Not found'); return; }
        const mime = {
            '.html': 'text/html', '.js': 'application/javascript',
            '.css': 'text/css',   '.ico': 'image/x-icon'
        }[path.extname(filePath)] || 'application/octet-stream';
        res.writeHead(200, { 'Content-Type': mime });
        res.end(data);
    });
});

const wss = new WebSocketServer({ server: httpServer });

// pcId -> { hasCamera, hasScreen, hasMic, version, lastSeen, connectedAt }
const pcs       = new Map();
const pcSockets = new Map(); // pcId -> ws
const dashboards = new Set();

// ── heartbeat: drop dead connections every 30s ────────────────────────────────
const PING_INTERVAL = 30_000;
setInterval(() => {
    for (const ws of wss.clients) {
        if (ws._dead) { ws.terminate(); continue; }
        ws._dead = true;
        ws.ping();
    }
}, PING_INTERVAL);

wss.on('connection', (ws, req) => {
    ws._dead = false;
    ws.on('pong', () => { ws._dead = false; });

    const url = new URL(req.url, `http://localhost:${PORT}`);

    // ── Dashboard client ──────────────────────────────────────────────────────
    if (url.searchParams.get('role') === 'dashboard') {
        dashboards.add(ws);

        // send current PC list immediately
        ws.send(JSON.stringify({
            type: 'pc_list',
            pcs: [...pcs.entries()].map(([id, v]) => ({ id, ...v }))
        }));

        ws.on('message', (data) => {
            try {
                const msg = JSON.parse(data);
                if (msg.type === 'command' && msg.target) {
                    const target = pcSockets.get(msg.target);
                    if (target && target.readyState === 1) {
                        const payload = { type: msg.cmd };
                        if (msg.value !== undefined) payload.value = msg.value;
                        target.send(JSON.stringify(payload));
                    }
                }
                // dashboard ping → pong with server timestamp
                if (msg.type === 'ping') {
                    ws.send(JSON.stringify({ type: 'pong', t: msg.t, serverTime: Date.now() }));
                }
            } catch (e) {}
        });

        ws.on('close', () => dashboards.delete(ws));
        return;
    }

    // ── PC client ─────────────────────────────────────────────────────────────
    let pcId = null;

    ws.on('message', (data) => {
        try {
            const msg = JSON.parse(data);

            if (msg.type === 'hello') {
                pcId = msg.id;
                const info = {
                    hasCamera:    !!msg.hasCamera,
                    hasScreen:    !!msg.hasScreen,
                    hasMic:       !!msg.hasMic,
                    version:      msg.version || '?',
                    lastSeen:     Date.now(),
                    connectedAt:  Date.now(),
                };
                pcs.set(pcId, info);
                pcSockets.set(pcId, ws);
                broadcast(dashboards, { type: 'pc_connected', id: pcId, ...info });
                console.log(`[+] ${pcId} | cam:${info.hasCamera} screen:${info.hasScreen} mic:${info.hasMic} v${info.version}`);
            }

            if (msg.type === 'frame' && msg.id) {
                updateLastSeen(msg.id);
                broadcastBinary(dashboards, { type: 'frame', id: msg.id, frame: msg.frame });
            }

            if (msg.type === 'screen_frame' && msg.id) {
                updateLastSeen(msg.id);
                broadcastBinary(dashboards, { type: 'screen_frame', id: msg.id, frame: msg.frame });
            }

            if (msg.type === 'audio_chunk' && msg.id) {
                broadcast(dashboards, { type: 'audio_chunk', id: msg.id, audio: msg.audio, sr: msg.sr || 16000 });
            }

            // PC can re-announce capabilities (e.g. mic confirmed after startup)
            if (msg.type === 'update_caps' && msg.id) {
                const existing = pcs.get(msg.id);
                if (existing) {
                    if (msg.hasMic  !== undefined) existing.hasMic  = !!msg.hasMic;
                    if (msg.hasCamera !== undefined) existing.hasCamera = !!msg.hasCamera;
                    broadcast(dashboards, { type: 'caps_update', id: msg.id, hasMic: existing.hasMic, hasCamera: existing.hasCamera });
                }
            }

        } catch (e) {
            console.error('[!] Bad message:', e.message);
        }
    });

    ws.on('close', () => {
        if (pcId) {
            pcs.delete(pcId);
            pcSockets.delete(pcId);
            broadcast(dashboards, { type: 'pc_disconnected', id: pcId });
            console.log(`[-] ${pcId} disconnected`);
        }
    });
});

function updateLastSeen(id) {
    const pc = pcs.get(id);
    if (pc) pc.lastSeen = Date.now();
}

function broadcast(clients, obj) {
    const msg = JSON.stringify(obj);
    for (const c of clients) {
        if (c.readyState === 1 && c.bufferedAmount < 512 * 1024) c.send(msg);
    }
}

// for large frame payloads — skip clients that are backed up
function broadcastBinary(clients, obj) {
    const msg = JSON.stringify(obj);
    for (const c of clients) {
        if (c.readyState === 1 && c.bufferedAmount < 2 * 1024 * 1024) c.send(msg);
    }
}

httpServer.listen(PORT, () => console.log(`[*] Server on port ${PORT}`));
