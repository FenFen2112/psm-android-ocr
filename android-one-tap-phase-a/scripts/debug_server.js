const http = require('http');
const url = require('url');
const os = require('os');

const PORT = 7478;
let lastData = { message: "Waiting for data from phone...", timestamp: Date.now() };

const server = http.createServer((req, res) => {
    const parsedUrl = url.parse(req.url, true);
    const pathname = parsedUrl.pathname;

    console.log(`[${new Date().toLocaleTimeString()}] ${req.method} ${pathname}`);

    res.setHeader('Access-Control-Allow-Origin', '*');
    res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
    res.setHeader('Access-Control-Allow-Headers', 'Content-Type');

    if (req.method === 'OPTIONS') {
        res.writeHead(204);
        res.end();
        return;
    }

    if (req.method === 'POST' && pathname === '/ingest') {
        let body = '';
        req.on('data', chunk => { body += chunk.toString(); });
        req.on('end', () => {
            try {
                lastData = JSON.parse(body);
                lastData.receivedAt = new Date().toLocaleTimeString();
                res.writeHead(200, { 'Content-Type': 'application/json' });
                res.end(JSON.stringify({ status: 'ok' }));
            } catch (e) {
                res.writeHead(400);
                res.end('Invalid JSON');
            }
        });
    } else if (req.method === 'GET' && (pathname === '/data' || pathname === '/latest')) {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify(lastData));
    } else if (req.method === 'GET' && (pathname === '/' || pathname === '/index.html')) {
        res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
        res.end(`
            <html>
            <head>
                <title>PSM Realtime Debug</title>
                <style>
                    body { font-family: sans-serif; background: #1e1e1e; color: #ddd; padding: 20px; }
                    .card { background: #2d2d2d; border-radius: 8px; padding: 20px; box-shadow: 0 4px 6px rgba(0,0,0,0.3); margin-bottom: 20px; }
                    pre { background: #000; padding: 10px; overflow: auto; color: #0f0; border-radius: 4px; white-space: pre-wrap; word-break: break-all; }
                    img { max-width: 100%; border: 2px solid #444; margin-top: 10px; display: block; border-radius: 4px; }
                    .status { color: #ffa500; font-weight: bold; }
                    .btn { background: #007bff; color: white; border: none; padding: 10px 20px; border-radius: 4px; cursor: pointer; text-decoration: none; display: inline-block; }
                </style>
                <script>
                    async function update() {
                        try {
                            const res = await fetch('/data');
                            const data = await res.json();
                            document.getElementById('time').innerText = data.receivedAt || 'None';
                            if (data.data) {
                                if (data.data.imageUrl) {
                                    document.getElementById('img').src = data.data.imageUrl;
                                    document.getElementById('img').style.display = 'block';
                                }
                                document.getElementById('raw').innerText = JSON.stringify(data, null, 2);
                            }
                        } catch(e) {}
                    }
                    setInterval(update, 1000);
                </script>
            </head>
            <body>
                <h1>PSM Realtime Debug Dashboard</h1>
                <div class="card">
                    <p>Last Update: <span id="time" class="status">Waiting...</span></p>
                    <a href="/latest" target="_blank" class="btn">Mở JSON cho AI (Copy link này gửi AI)</a>
                </div>
                <div class="card">
                    <h3>Visual Preview:</h3>
                    <img id="img" style="display:none" />
                </div>
                <div class="card">
                    <h3>Raw Data (JSON):</h3>
                    <pre id="raw"></pre>
                </div>
            </body>
            </html>
        `);
    } else {
        res.writeHead(404);
        res.end(`Not Found: ${pathname}`);
    }
});

server.listen(PORT, '0.0.0.0', () => {
    const lanUrls = Object.values(os.networkInterfaces())
        .flat()
        .filter((item) => item && item.family === 'IPv4' && !item.internal)
        .map((item) => `http://${item.address}:${PORT}/ingest`);

    console.log(`
🚀 Debug Server UPDATED!
👉 Dashboard: http://localhost:${PORT}/
👉 AI Endpoint: http://localhost:${PORT}/latest
📱 Ingest URL(s):
${lanUrls.map((item) => `   ${item}`).join('\n') || `   http://<PC_IP>:${PORT}/ingest`}
    `);
});
