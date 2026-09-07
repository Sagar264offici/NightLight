/* Local verification runner (not part of the app): boots MongoMemoryServer
   then starts dist/local.js so user-data endpoints work without Atlas. */
const { MongoMemoryServer } = require('mongodb-memory-server');

(async () => {
  try {
    const mongod = await MongoMemoryServer.create({ instance: { dbName: 'nightlight' } });
    const uri = mongod.getUri('nightlight');
    console.log('[runner] mongod ready');
    process.env.MONGODB_URI = uri;
    process.env.MONGODB_DB_NAME = 'nightlight';
    process.env.PORT = '8787';
    process.env.NODE_ENV = 'development';
    require('./dist/local.js');
  } catch (e) {
    console.error('[runner] FAILED:', e.message);
    process.exit(1);
  }
})();
