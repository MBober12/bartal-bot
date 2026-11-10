-- Создание таблиц если они еще не существуют

CREATE TABLE IF NOT EXISTS documents (
    docId INTEGER PRIMARY KEY AUTOINCREMENT,
    fileId TEXT,
    projectTableName TEXT,
    isPhoto INTEGER,
    details TEXT,
    typeOfFile TEXT,
    uniqueId TEXT,
    reason TEXT,
    statusNote TEXT,
    stage TEXT NOT NULL DEFAULT 'SUBMITTED',
    user TEXT,
    acceptor TEXT,
    registrar TEXT,
    receiver TEXT,
    acceptors TEXT,
    registrars TEXT,
    receivers TEXT,
    userTime INTEGER,
    acceptorTime INTEGER,
    registrarTime INTEGER,
    receiverTime INTEGER,
    isPayed INTEGER NOT NULL DEFAULT 0,
    needsToBeRegistered INTEGER NOT NULL DEFAULT 0,
    isBannedByAcceptor INTEGER NOT NULL DEFAULT 0,
    isBannedByRegistrar INTEGER NOT NULL DEFAULT 0,
    isBannedByReceiver INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS users (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    chatId TEXT UNIQUE,
    name TEXT,
    state TEXT,
    lastMessage TEXT,
    mesIdsAndDocIds TEXT,
    showInfo INTEGER NOT NULL DEFAULT 0,
    isUser INTEGER NOT NULL DEFAULT 0,
    isAdmin INTEGER NOT NULL DEFAULT 0,
    isAcceptor INTEGER NOT NULL DEFAULT 0,
    isReceiver INTEGER NOT NULL DEFAULT 0,
    isRegistrar INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS projects (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT,
    tableName TEXT UNIQUE,
    description TEXT,
    isArchived INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS reminders (
    remindsPassed INTEGER,
    chatId TEXT,
    messageId INTEGER,
    stage TEXT,
    docId INTEGER
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_users_chatId ON users (chatId);
CREATE UNIQUE INDEX IF NOT EXISTS idx_projects_tableName ON projects (tableName);
