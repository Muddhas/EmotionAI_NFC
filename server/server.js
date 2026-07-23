const express = require('express');
const Database = require('better-sqlite3');
const cors = require('cors');
const { v4: uuidv4 } = require('uuid');
const path = require('path');

// ========================================
// 初始化
// ========================================
const app = express();
const PORT = 3000;

app.use(cors());
app.use(express.json());

// ========================================
// 数据库初始化
// ========================================
const db = new Database(path.join(__dirname, 'emotion_ai.db'));
db.pragma('journal_mode = WAL');
db.pragma('foreign_keys = ON');

db.exec(`
    CREATE TABLE IF NOT EXISTS users (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        username TEXT UNIQUE NOT NULL,
        token TEXT NOT NULL,
        created_at DATETIME DEFAULT CURRENT_TIMESTAMP
    );

    CREATE TABLE IF NOT EXISTS ai_characters (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        name TEXT NOT NULL,
        description TEXT,
        personality TEXT,
        avatar_color TEXT DEFAULT '#6750A4'
    );

    CREATE TABLE IF NOT EXISTS chips (
        id TEXT PRIMARY KEY,
        chip_type TEXT NOT NULL DEFAULT '424',
        uid TEXT,
        bound_user_id INTEGER,
        ai_character_id INTEGER NOT NULL,
        first_bound_at DATETIME,
        last_access_at DATETIME,
        is_genuine INTEGER DEFAULT 0,
        FOREIGN KEY (bound_user_id) REFERENCES users(id),
        FOREIGN KEY (ai_character_id) REFERENCES ai_characters(id)
    );

    CREATE INDEX IF NOT EXISTS idx_chips_bound_user ON chips(bound_user_id);
    CREATE INDEX IF NOT EXISTS idx_chips_uid ON chips(uid);
`);

// 预置 AI 角色数据
const charCount = db.prepare('SELECT COUNT(*) as count FROM ai_characters').get();
if (charCount.count === 0) {
    const insertChar = db.prepare(
        'INSERT INTO ai_characters (id, name, description, personality, avatar_color) VALUES (?, ?, ?, ?, ?)'
    );
    insertChar.run(1, '小林', '温柔贴心的日常陪伴', '温柔、细腻、善解人意', '#6750A4');
    insertChar.run(2, '苏菲', '知性优雅的深度对话', '博学、冷静、有见解', '#006D40');
    insertChar.run(3, '小阳', '阳光开朗的元气伙伴', '热情、幽默、正能量', '#B64500');
    insertChar.run(4, '月月', '古灵精怪的脑洞少女', '俏皮、创意、不按常理', '#9C27B0');
    insertChar.run(5, '老陈', '成熟稳重的知心大哥', '靠谱、理性、话少但暖', '#004D73');
}

// ========================================
// 中间件：Token 验证
// ========================================
function authMiddleware(req, res, next) {
    const authHeader = req.headers.authorization;
    if (!authHeader || !authHeader.startsWith('Bearer ')) {
        return res.status(401).json({ success: false, error: '未提供认证令牌' });
    }
    const token = authHeader.split(' ')[1];
    const user = db.prepare('SELECT id, username FROM users WHERE token = ?').get(token);
    if (!user) {
        return res.status(401).json({ success: false, error: '令牌无效或已过期' });
    }
    req.user = user;
    next();
}

// ========================================
// 认证 API
// ========================================

// POST /api/auth/register - 注册新用户
app.post('/api/auth/register', (req, res) => {
    const { username } = req.body;
    if (!username || !username.trim()) {
        return res.status(400).json({ success: false, error: '用户名不能为空' });
    }

    try {
        const token = uuidv4();
        const result = db.prepare('INSERT INTO users (username, token) VALUES (?, ?)').run(username.trim(), token);
        res.status(201).json({
            success: true,
            data: { userId: result.lastInsertRowid, username: username.trim(), token }
        });
    } catch (err) {
        if (err.message.includes('UNIQUE constraint')) {
            return res.status(409).json({ success: false, error: '用户名已存在' });
        }
        res.status(500).json({ success: false, error: '注册失败' });
    }
});

// POST /api/auth/login - 用户登录
app.post('/api/auth/login', (req, res) => {
    const { username } = req.body;
    if (!username || !username.trim()) {
        return res.status(400).json({ success: false, error: '用户名不能为空' });
    }

    const user = db.prepare('SELECT id, username, token FROM users WHERE username = ?').get(username.trim());
    if (!user) {
        return res.status(404).json({ success: false, error: '用户不存在' });
    }

    res.json({ success: true, data: { userId: user.id, username: user.username, token: user.token } });
});

// GET /api/auth/users - 获取已注册用户列表
app.get('/api/auth/users', (req, res) => {
    const users = db.prepare('SELECT id AS userId, username FROM users ORDER BY username').all();
    res.json({ success: true, data: users });
});

// ========================================
// NFC 芯片 API
// ========================================

// GET /api/chips/:chipId - 查询芯片信息
app.get('/api/chips/:chipId', (req, res) => {
    const { chipId } = req.params;

    let chip = db.prepare('SELECT * FROM chips WHERE id = ?').get(chipId);

    if (!chip) {
        const characters = db.prepare('SELECT id AS aiCharacterId, name, description, personality, avatar_color AS avatarColor FROM ai_characters').all();
        return res.json({
            success: true,
            data: { chipId, isBound: false, isNew: true, aiCharacters: characters }
        });
    }

    const character = db.prepare('SELECT id AS aiCharacterId, name, description, personality, avatar_color AS avatarColor FROM ai_characters WHERE id = ?').get(chip.ai_character_id);

    if (chip.bound_user_id === null) {
        const characters = db.prepare('SELECT id AS aiCharacterId, name, description, personality, avatar_color AS avatarColor FROM ai_characters').all();
        return res.json({ success: true, data: { chipId, isBound: false, aiCharacters: characters } });
    }

    const boundUser = db.prepare('SELECT username FROM users WHERE id = ?').get(chip.bound_user_id);

    const response = {
        success: true,
        data: {
            chipId,
            chipType: chip.chip_type,
            isBound: true,
            isCurrentUser: false,
            aiCharacter: character,
            boundTo: boundUser?.username || '未知用户'
        }
    };

    const authHeader = req.headers.authorization;
    if (authHeader && authHeader.startsWith('Bearer ')) {
        const token = authHeader.split(' ')[1];
        const user = db.prepare('SELECT id FROM users WHERE token = ?').get(token);
        if (user && user.id === chip.bound_user_id) {
            response.data.isCurrentUser = true;
            delete response.data.boundTo;
        }
    }

    res.json(response);
});

// POST /api/chips/bind - 绑定芯片到用户
app.post('/api/chips/bind', authMiddleware, (req, res) => {
    const { chipId, aiCharacterId } = req.body;
    if (!chipId || !aiCharacterId) {
        return res.status(400).json({ success: false, error: '缺少必要参数 chipId 或 aiCharacterId' });
    }

    const character = db.prepare('SELECT id FROM ai_characters WHERE id = ?').get(aiCharacterId);
    if (!character) {
        return res.status(404).json({ success: false, error: 'AI 角色不存在' });
    }

    const existing = db.prepare('SELECT * FROM chips WHERE id = ?').get(chipId);
    if (existing && existing.bound_user_id !== null) {
        return res.status(409).json({ success: false, error: '该芯片已被其他用户绑定' });
    }

    const now = new Date().toISOString();
    if (existing) {
        db.prepare('UPDATE chips SET bound_user_id = ?, first_bound_at = ?, last_access_at = ? WHERE id = ?')
            .run(req.user.id, now, now, chipId);
    } else {
        db.prepare('INSERT INTO chips (id, chip_type, bound_user_id, ai_character_id, first_bound_at, last_access_at) VALUES (?, ?, ?, ?, ?, ?)')
            .run(chipId, '424', req.user.id, aiCharacterId, now, now);
    }

    res.json({ success: true, data: { chipId, boundUserId: req.user.id, aiCharacterId } });
});

// POST /api/chips/verify - 424 防伪验证
app.post('/api/chips/verify', authMiddleware, (req, res) => {
    const { chipId, uid, signature } = req.body;
    if (!chipId || !uid || !signature) {
        return res.status(400).json({ success: false, error: '缺少必要参数 chipId/uid/signature' });
    }

    const chip = db.prepare('SELECT * FROM chips WHERE id = ?').get(chipId);

    if (!chip) {
        return res.json({ success: true, data: { isGenuine: false, message: '芯片未注册' } });
    }

    // TODO: 接入真实 AES-128 CMAC 验证
    // 当前 Demo 版本默认验证通过
    const isGenuine = true;

    if (isGenuine) {
        db.prepare('UPDATE chips SET is_genuine = 1 WHERE id = ?').run(chipId);
        db.prepare('UPDATE chips SET uid = ? WHERE id = ?').run(uid, chipId);
    }

    res.json({ success: true, data: { isGenuine, message: isGenuine ? '验证通过' : '验证失败' } });
});

// ========================================
// 用户管理 API
// ========================================

// GET /api/user/chips - 获取当前用户绑定的所有芯片
app.get('/api/user/chips', authMiddleware, (req, res) => {
    const chips = db.prepare(`
        SELECT c.id AS chipId, c.chip_type AS chipType, c.first_bound_at AS boundAt,
               a.id AS aiId, a.name, a.description, a.personality, a.avatar_color AS avatarColor
        FROM chips c
        JOIN ai_characters a ON c.ai_character_id = a.id
        WHERE c.bound_user_id = ?
        ORDER BY c.first_bound_at DESC
    `).all(req.user.id);

    res.json({
        success: true,
        data: chips.map(chip => ({
            chipId: chip.chipId,
            chipType: chip.chipType,
            boundAt: chip.boundAt,
            aiCharacter: {
                id: chip.aiId,
                name: chip.name,
                description: chip.description,
                personality: chip.personality,
                avatarColor: chip.avatarColor
            }
        }))
    });
});

// ========================================
// 启动服务器
// ========================================
const { networkInterfaces } = require('os');
const nets = networkInterfaces();
let localIp = 'localhost';
for (const name of Object.keys(nets)) {
    for (const net of nets[name]) {
        if (net.family === 'IPv4' && !net.internal) {
            localIp = net.address;
            break;
        }
    }
}

app.listen(PORT, '0.0.0.0', () => {
    console.log(`\n========================================`);
    console.log(`  Emotion AI NFC Server 已启动`);
    console.log(`========================================`);
    console.log(`  本地地址:  http://localhost:${PORT}`);
    console.log(`  局域网地址: http://${localIp}:${PORT}`);
    console.log(`  (手机连接此地址访问 API)`);
    console.log(`========================================\n`);
});
