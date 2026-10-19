// Bootstrap for the browser-E2E stack: one local operator with a known
// password. The draft weeks are NOT inserted here — the seed-generate service
// creates them through the real generation pipeline against the stub provider.
import pg from 'pg';

const databaseURL = process.env.DATABASE_URL;
if (!databaseURL) { console.error('DATABASE_URL required'); process.exit(1); }

const db = new pg.Pool({ connectionString: databaseURL });
try {
  const admins = await import('/app/dist/src/admins-admin.js');
  let adminID;
  try {
    adminID = (await admins.addAdmin(db, 'local', 'e2e-operator', 'e2e@example.test')).id;
  } catch (error) {
    console.error('addAdmin:', error instanceof Error ? error.message : error);
    adminID = (await admins.listAdmins(db)).find(admin => admin.subject === 'e2e-operator')?.id;
  }
  if (!adminID) throw new Error('operator bootstrap failed');
  await admins.setAdminPassword(db, adminID, 'e2e-browser-password');
  console.log(`operator ready (${adminID})`);
} finally {
  await db.end();
}
