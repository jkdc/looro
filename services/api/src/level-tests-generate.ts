import { connectDatabase } from './db.js';
import { runLevelTestGenerationJob } from './level-tests-generation-jobs.js';
import { weekIndex } from './level-tests.js';

/** Weekly LLM generation CLI (level-tests-llm, cron option A):
    `npm run leveltests-generate [--force] [--langs en,es] [--levels A1,B1]`
    Reads DATABASE_URL + LEVELTEST_* env. Exit 0 when every requested exam
    ended approved/draft/exists; exit 1 when any level failed (the serving
    endpoints fail closed to the deterministic composition regardless). */

const args = process.argv.slice(2);
const flag = (name: string) => args.includes(name);
const value = (name: string, fallback: string) => {
  const index = args.indexOf(name);
  return index >= 0 && args[index + 1] ? args[index + 1]! : fallback;
};

const languages = value('--langs', 'en,es').split(',').map(entry => entry.trim()).filter(Boolean);
const levels = value('--levels', 'A1,A2,B1').split(',').map(entry => entry.trim()).filter(Boolean);
const databaseURL = process.env.DATABASE_URL;
if (!databaseURL) { console.error('DATABASE_URL required'); process.exit(1); }

const db = connectDatabase(databaseURL);
try {
  const summary: unknown[] = [];
  let failures = 0;
  for (const languageID of languages) {
    for (const level of levels) {
      // Pace the calls: the coding plan has RPM limits.
      await new Promise(resolve => setTimeout(resolve, 5000));
      try {
        const outcome = await runLevelTestGenerationJob(db, process.env, { languageID, level, weekIndex: weekIndex(new Date()), force: flag('--force') });
        summary.push(outcome);
        if (['failed', 'unknown'].includes(outcome.status)) failures += 1;
        console.error(`${languageID}/${level}: ${outcome.status}${outcome.errors?.length ? ` — ${outcome.errors.join('; ')}` : ''}`);
      } catch (error) {
        failures += 1;
        console.error(`${languageID}/${level}: failed — generation_failed`);
      }
    }
  }
  console.log(JSON.stringify(summary));
  process.exitCode = failures > 0 ? 1 : 0;
} finally { await db.end(); }
