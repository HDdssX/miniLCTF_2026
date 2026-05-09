const express = require('express');
const { AsyncLocalStorage } = require('async_hooks');
const vm = require('vm');
const crypto = require('crypto');
const path = require('path');
const http = require('http');

const app = express();
app.use(express.static(path.join(__dirname, 'public')));
app.use(express.json());

const BOT_INTERVAL_MS = 1000;
const VERIFY_WINDOW_MS = 40;
const INTERNAL_VERIFY_TOKEN = crypto.randomBytes(24).toString('hex');

const als = new AsyncLocalStorage();
const sessions = new Map();
const BLOCKED_TOKENS = ['then', 'constructor', 'process', 'require', 'eval', 'catch'];

let pendingPromotion = null;
let lastVerifyLog = `internal heartbeat warming up (${BOT_INTERVAL_MS}ms cadence)`;

const SANDBOX_SOURCE = String.raw`
const BLOCKED_TOKENS = ['then', 'constructor', 'process', 'require', 'eval', 'catch'];

function walkAst(node, visit) {
    if (!node || typeof node !== 'object') return;
    visit(node);

    for (const value of Object.values(node)) {
        if (Array.isArray(value)) {
            for (const item of value) {
                if (item && typeof item.type === 'string') walkAst(item, visit);
            }
            continue;
        }

        if (value && typeof value.type === 'string') walkAst(value, visit);
    }
}

function foldStaticString(node) {
    if (!node || typeof node !== 'object') return null;

    if (node.type === 'Literal') {
        return typeof node.value === 'string' ? node.value : null;
    }

    if (node.type === 'TemplateLiteral' && node.expressions.length === 0) {
        return node.quasis.map(quasi => quasi.value.cooked || '').join('');
    }

    if (node.type === 'BinaryExpression' && node.operator === '+') {
        const left = foldStaticString(node.left);
        const right = foldStaticString(node.right);
        return typeof left === 'string' && typeof right === 'string' ? left + right : null;
    }

    if (node.type === 'ParenthesizedExpression') {
        return foldStaticString(node.expression);
    }

    return null;
}

function containsBlockedAstPattern(ast) {
    let blocked = false;

    walkAst(ast, node => {
        if (blocked) return;

        const folded = foldStaticString(node);
        if (typeof folded === 'string') {
            const lowered = folded.toLowerCase();
            blocked = BLOCKED_TOKENS.some(token => lowered.includes(token));
            return;
        }

        if (node.type === 'Identifier') {
            blocked = BLOCKED_TOKENS.includes(node.name);
            return;
        }

        if (node.type === 'MemberExpression' && !node.computed && node.property && node.property.type === 'Identifier') {
            blocked = BLOCKED_TOKENS.includes(node.property.name);
        }
    });

    return blocked;
}

app.post('/api/run', async (req, res) => {
    const store = als.getStore();
    if (!store || store.role !== 'admin') return res.status(403).json({ error: 'Denied' });

    const { code } = req.body;
    if (typeof code !== 'string' || code.length > 255) return res.status(400).send('WAF: Length');

    const blacklist = /then|constructor|process|require|eval|=>|catch/g;
    if (blacklist.test(code)) return res.status(400).send('WAF: Keyword');

    try {
        const acorn = require('acorn');
        const ast = acorn.parse(code, { ecmaVersion: 2020 });
        if (containsBlockedAstPattern(ast)) return res.status(400).send('WAF: Fold');
    } catch (e) {
        return res.status(400).send('Syntax Error');
    }

    const context = vm.createContext(Object.create(null));
    try {
        const result = await vm.runInContext(code, context, { timeout: 1000 });
        store.lastRunOutput = String(result);
        res.status(204).end();
    } catch (e) {
        res.status(500).send('Runtime Error');
    }
});

app.get('/api/run', (req, res) => {
    const store = als.getStore();
    if (!store || store.role !== 'admin') return res.status(403).json({ error: 'Denied' });

    const cursor = Number.parseInt(req.query.cursor, 10);
    if (!Number.isInteger(cursor) || cursor < 0) {
        return res.status(400).json({ error: 'Cursor required' });
    }

    const output = typeof store.lastRunOutput === 'string' ? store.lastRunOutput : '';
    res.json({ char: output[cursor] || '' });
});
`;

function createSession(sid) {
    return {
        role: 'guest',
        sid,
        lastRunOutput: '',
    };
}

function walkAst(node, visit) {
    if (!node || typeof node !== 'object') {
        return;
    }

    visit(node);

    for (const value of Object.values(node)) {
        if (Array.isArray(value)) {
            for (const item of value) {
                if (item && typeof item.type === 'string') {
                    walkAst(item, visit);
                }
            }
            continue;
        }

        if (value && typeof value.type === 'string') {
            walkAst(value, visit);
        }
    }
}

function foldStaticString(node) {
    if (!node || typeof node !== 'object') {
        return null;
    }

    if (node.type === 'Literal') {
        return typeof node.value === 'string' ? node.value : null;
    }

    if (node.type === 'TemplateLiteral' && node.expressions.length === 0) {
        return node.quasis.map(quasi => quasi.value.cooked || '').join('');
    }

    if (node.type === 'BinaryExpression' && node.operator === '+') {
        const left = foldStaticString(node.left);
        const right = foldStaticString(node.right);
        if (typeof left === 'string' && typeof right === 'string') {
            return left + right;
        }
    }

    if (node.type === 'ParenthesizedExpression') {
        return foldStaticString(node.expression);
    }

    return null;
}

function containsBlockedAstPattern(ast) {
    let blocked = false;

    walkAst(ast, node => {
        if (blocked) {
            return;
        }

        const folded = foldStaticString(node);
        if (typeof folded === 'string') {
            const lowered = folded.toLowerCase();
            blocked = BLOCKED_TOKENS.some(token => lowered.includes(token));
            return;
        }

        if (node.type === 'Identifier') {
            blocked = BLOCKED_TOKENS.includes(node.name);
            return;
        }

        if (node.type === 'MemberExpression' && !node.computed && node.property && node.property.type === 'Identifier') {
            blocked = BLOCKED_TOKENS.includes(node.property.name);
        }
    });

    return blocked;
}

app.use((req, res, next) => {
    let sid = req.header('X-Session-Id');
    if (!sid || !sessions.has(sid)) {
        sid = crypto.randomBytes(16).toString('hex');
        sessions.set(sid, createSession(sid));
    }

    res.setHeader('X-Session-Id', sid);
    als.run(sessions.get(sid), () => next());
});

app.get('/api/stats', (req, res) => {
    res.json({
        status: 'running',
        last_verify_activity: lastVerifyLog,
    });
});

app.get('/api/me', (req, res) => {
    const store = als.getStore();
    res.json({ role: store.role, sid: store.sid });
});

app.get('/admin', (req, res) => {
    const store = als.getStore();
    if (!store || store.role !== 'admin') {
        return res.status(404).send('Not Found');
    }

    const html = `
<!DOCTYPE html>
<html>
<body>
    <h1>Admin Console</h1>
    <p>Diagnostics namespace mounted.</p>
    <code>/admin/debug/*</code>
</body>
</html>`;

    res.type('text/html').send(html);
});

app.get('/admin/debug/source', (req, res) => {
    const store = als.getStore();
    if (!store || store.role !== 'admin') {
        return res.status(404).send('Not Found');
    }

    const html = `
<!DOCTYPE html>
<html>
<body>
    <h1>Admin Debug Snapshot</h1>
    <p>Sandbox status: operational.</p>
    <pre>${SANDBOX_SOURCE}</pre>
</body>
</html>`;

    res.type('text/html').send(html);
});

app.post('/api/verify', async (req, res) => {
    const store = als.getStore();
    const isInternalBot = req.header('X-Internal-Verify') === INTERNAL_VERIFY_TOKEN;
    const now = Date.now();

    if (isInternalBot) {
        if (pendingPromotion && pendingPromotion.expiresAt > now) {
            pendingPromotion.store.role = 'admin';
            lastVerifyLog = `heartbeat promoted sid=${pendingPromotion.sid} at ${new Date(now).toISOString()}`;
            pendingPromotion = null;
            return res.json({ msg: 'Local node verified' });
        }

        lastVerifyLog = `heartbeat idle at ${new Date(now).toISOString()}`;
        pendingPromotion = null;
        return res.json({ msg: 'Verifier heartbeat idle' });
    }

    pendingPromotion = {
        sid: store.sid,
        store,
        expiresAt: now + VERIFY_WINDOW_MS,
    };

    lastVerifyLog = `pending sid=${store.sid} until ${new Date(pendingPromotion.expiresAt).toISOString()}`;

    await new Promise(resolve => setTimeout(resolve, VERIFY_WINDOW_MS));

    if (pendingPromotion && pendingPromotion.sid === store.sid && pendingPromotion.expiresAt <= Date.now()) {
        pendingPromotion = null;
    }

    res.json({ msg: 'Queued for verifier heartbeat' });
});

setInterval(() => {
    const req = http.request({
        hostname: '127.0.0.1',
        port: 5000,
        path: '/api/verify',
        method: 'POST',
        headers: {
            'X-Session-Id': 'BOT',
            'X-Internal-Verify': INTERNAL_VERIFY_TOKEN,
        },
    });

    req.on('error', () => {});
    req.end();
}, BOT_INTERVAL_MS);

app.post('/api/run', async (req, res) => {
    const store = als.getStore();
    if (!store || store.role !== 'admin') {
        return res.status(403).json({ error: 'Denied' });
    }

    const { code } = req.body;
    if (typeof code !== 'string' || code.length > 255) {
        return res.status(400).send('WAF: Length');
    }

    const blacklist = /then|constructor|process|require|eval|=>|catch/g;
    if (blacklist.test(code)) {
        return res.status(400).send('WAF: Keyword');
    }

    try {
        const acorn = require('acorn');
        const ast = acorn.parse(code, { ecmaVersion: 2020 });
        if (containsBlockedAstPattern(ast)) {
            return res.status(400).send('WAF: Fold');
        }
    } catch (e) {
        return res.status(400).send('Syntax Error');
    }

    const context = vm.createContext(Object.create(null));
    try {
        const result = await vm.runInContext(code, context, { timeout: 1000 });
        store.lastRunOutput = String(result);
        res.status(204).end();
    } catch (e) {
        res.status(500).send('Runtime Error');
    }
});

app.get('/api/run', (req, res) => {
    const store = als.getStore();
    if (!store || store.role !== 'admin') {
        return res.status(403).json({ error: 'Denied' });
    }

    const cursor = Number.parseInt(req.query.cursor, 10);
    if (!Number.isInteger(cursor) || cursor < 0) {
        return res.status(400).json({ error: 'Cursor required' });
    }

    const output = typeof store.lastRunOutput === 'string' ? store.lastRunOutput : '';
    res.json({ char: output[cursor] || '' });
});

app.listen(5000, '0.0.0.0', () => console.log('Listening on 5000'));
