const express = require('express');
const crypto = require('crypto');

const app = express();
app.use(express.json({ limit: '1mb' }));

const PORT = process.env.PORT || 3000;
const DEVICE_ID = 'orsay-main';
const commandQueues = new Map();
const sseClients = new Map();
const lastStatus = { device_id: DEVICE_ID, last_sync: null, last_command: null };

function queueFor(deviceId) {
  const id = deviceId || DEVICE_ID;
  if (!commandQueues.has(id)) commandQueues.set(id, []);
  return commandQueues.get(id);
}

function rpcResult(id, result) {
  return { jsonrpc: '2.0', id, result };
}

function rpcError(id, code, message) {
  return { jsonrpc: '2.0', id, error: { code, message } };
}

function toolsList() {
  return [
    {
      name: 'plan_mark_streets',
      description: "Marque une ou plusieurs rues d'Orsay comme effectuées. La couleur est déterminée automatiquement par la date dans l'application Android.",
      inputSchema: {
        type: 'object',
        properties: {
          streets: { type: 'array', items: { type: 'string' }, minItems: 1, description: 'Noms des rues à marquer.' },
          date: { type: 'string', description: 'Date au format YYYY-MM-DD. Facultatif : aujourd’hui par défaut.' }
        },
        required: ['streets'],
        additionalProperties: false
      }
    },
    {
      name: 'plan_get_status',
      description: "Retourne l'état du pont MCP Plan Travail Orsay et les commandes en attente pour le téléphone.",
      inputSchema: { type: 'object', properties: {}, additionalProperties: false }
    },
    {
      name: 'plan_clear_pending',
      description: 'Supprime toutes les commandes MCP encore en attente avant leur récupération par le téléphone.',
      inputSchema: { type: 'object', properties: {}, additionalProperties: false }
    }
  ];
}

function executeTool(name, args = {}) {
  if (name === 'plan_mark_streets') {
    const streets = Array.isArray(args.streets) ? args.streets.map(String).map(s => s.trim()).filter(Boolean) : [];
    if (!streets.length) throw new Error('Aucune rue fournie');
    const date = /^\d{4}-\d{2}-\d{2}$/.test(String(args.date || ''))
      ? String(args.date)
      : new Date().toISOString().slice(0, 10);
    const cmd = { id: crypto.randomUUID(), streets, date, created_at: new Date().toISOString() };
    queueFor(DEVICE_ID).push(cmd);
    lastStatus.last_command = cmd;
    return {
      content: [{ type: 'text', text: `${streets.length} rue(s) mise(s) en attente pour Plan Travail Orsay au ${date}: ${streets.join(', ')}` }],
      structuredContent: { queued: true, device_id: DEVICE_ID, command: cmd }
    };
  }
  if (name === 'plan_get_status') {
    const pending = queueFor(DEVICE_ID);
    return {
      content: [{ type: 'text', text: `Pont MCP actif. ${pending.length} commande(s) en attente pour le téléphone.` }],
      structuredContent: { ok: true, device_id: DEVICE_ID, pending_count: pending.length, last_sync: lastStatus.last_sync, last_command: lastStatus.last_command }
    };
  }
  if (name === 'plan_clear_pending') {
    commandQueues.set(DEVICE_ID, []);
    return { content: [{ type: 'text', text: 'Commandes en attente supprimées.' }], structuredContent: { cleared: true } };
  }
  throw new Error(`Outil inconnu: ${name}`);
}

function handleRpc(body) {
  const id = body && Object.prototype.hasOwnProperty.call(body, 'id') ? body.id : null;
  const method = body?.method;
  if (method === 'initialize') {
    return rpcResult(id, {
      protocolVersion: '2025-06-18',
      capabilities: { tools: { listChanged: false } },
      serverInfo: { name: 'Plan Travail Orsay', version: '1.0.0' }
    });
  }
  if (method === 'ping') return rpcResult(id, {});
  if (method === 'tools/list') return rpcResult(id, { tools: toolsList() });
  if (method === 'tools/call') {
    try {
      return rpcResult(id, executeTool(body?.params?.name, body?.params?.arguments || {}));
    } catch (e) {
      return rpcResult(id, { isError: true, content: [{ type: 'text', text: e.message || 'Erreur outil' }] });
    }
  }
  if (method && method.startsWith('notifications/')) return null;
  return rpcError(id, -32601, `Méthode non supportée: ${method || 'vide'}`);
}

app.get('/', (req, res) => {
  res.json({
    name: 'Plan Travail Orsay MCP',
    ok: true,
    mcp_sse: '/sse',
    mcp_http: '/mcp',
    android_commands: '/commands?device_id=orsay-main'
  });
});

app.get('/health', (req, res) => res.json({ ok: true, service: 'plan-travail-orsay-mcp' }));

app.post('/mcp', (req, res) => {
  const answer = handleRpc(req.body);
  if (answer === null) return res.status(202).end();
  res.setHeader('Content-Type', 'application/json');
  res.json(answer);
});

app.get('/sse', (req, res) => {
  const sessionId = crypto.randomUUID();
  res.set({
    'Content-Type': 'text/event-stream',
    'Cache-Control': 'no-cache, no-transform',
    'Connection': 'keep-alive',
    'X-Accel-Buffering': 'no'
  });
  res.flushHeaders?.();
  sseClients.set(sessionId, res);
  res.write(`event: endpoint\ndata: /messages?sessionId=${encodeURIComponent(sessionId)}\n\n`);
  const keepAlive = setInterval(() => res.write(': keepalive\n\n'), 15000);
  req.on('close', () => {
    clearInterval(keepAlive);
    sseClients.delete(sessionId);
  });
});

app.post('/messages', (req, res) => {
  const sessionId = String(req.query.sessionId || '');
  const client = sseClients.get(sessionId);
  if (!client) return res.status(404).json({ error: 'session_not_found' });
  const answer = handleRpc(req.body);
  if (answer !== null) client.write(`event: message\ndata: ${JSON.stringify(answer)}\n\n`);
  res.status(202).end();
});

app.get('/commands', (req, res) => {
  const deviceId = String(req.query.device_id || DEVICE_ID);
  const q = queueFor(deviceId);
  const commands = q.splice(0, q.length);
  lastStatus.last_sync = new Date().toISOString();
  res.json({ device_id: deviceId, commands });
});

app.get('/status', (req, res) => {
  const pending = queueFor(DEVICE_ID);
  res.json({ ok: true, device_id: DEVICE_ID, pending_count: pending.length, last_sync: lastStatus.last_sync, last_command: lastStatus.last_command });
});

app.use((err, req, res, next) => {
  console.error(err);
  res.status(500).json({ error: 'server_error', message: err.message || 'Erreur serveur' });
});

app.listen(PORT, '0.0.0.0', () => console.log(`Plan Travail Orsay MCP listening on ${PORT}`));
