// Stub LLM provider for the browser E2E (T11 task 5): an OpenAI-compatible
// /chat/completions endpoint. Two jobs, no real provider and no external
// traffic:
//   1. the panel's saved-configuration connection test ("Reply with OK.") → 200;
//   2. the seed generation CLI (seed-generate service): section-aware VALID
//      English pools, so drafts are created through the real generation
//      pipeline (validator included) instead of hand-inserted SQL rows.
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';

let dailyFixture;
try { dailyFixture = JSON.parse(await readFile('/daily-fixture.json', 'utf8')); }
catch { /* The legacy exam-generation stub does not mount the daily fixture. */ }

const dailyCandidate = () => {
  const { topic, objective, expressions, listening, speaking, goals, practice } = dailyFixture;
  const content = { topic, objective, expressions, listening: { transcript: listening.transcript, questions: listening.questions },
    speaking, goals, practice };
  const answerKeys = questions => questions.map(question => ({ questionId: question.id,
    explanation: { en: 'The fixture answer is supported by the lesson.', es: 'La respuesta de prueba está respaldada por la lección.' },
    targetExpressionIds: [expressions[0].id],
    ...(question.kind === 'single_choice' ? { acceptedOptionId: question.options[0].id } : { acceptedAnswers: ['a clear answer'] }) }));
  return { content, privatePayload: { listening: answerKeys(listening.questions), practice: answerKeys(practice.questions) } };
};

const choice = (prompt, answer, wrong) => ({
  kind: 'choice', prompt, options: [answer, ...wrong.slice(0, 3)], answers: [answer],
  explain: `The correct choice is ${answer}.`,
});
const recognition = Array.from({ length: 12 }, (_, i) =>
  choice(`She ___ a doctor, example number ${i}.`, 'is', ['are', 'am', 'be']));
const writing = Array.from({ length: 3 }, (_, i) => ({
  kind: 'written', prompt: `Complete the phrase ${i}: I have ___ idea.`, answers: ['an'],
  explain: 'An is used before a vowel sound.',
}));
const reading = [{
  title: 'The new job',
  text: 'Last year Marta moved to Berlin for a new job, and the first months were not easy for her at all. She now works at a small design studio near the main station and shares a bright flat with two close friends from her college years.',
  items: [
    choice('Why did Marta move to Berlin?', 'For a new job', ['For fun', 'For rain', 'For a pet']),
    choice('Where does she work now?', 'A design studio', ['A bank', 'A park', 'A hotel']),
    choice('Who does she live with?', 'Two close friends', ['Her sister', 'Alone', 'Her parents']),
  ],
}, {
  title: 'The weekend plan',
  text: 'This weekend we are visiting the market downtown with our friends. Lucas wants to buy a warm jacket, and I am looking for a present for my sister before we all have lunch together near the river.',
  items: [
    choice('Where are they going this weekend?', 'The market', ['The beach', 'A museum', 'A concert']),
    choice('What does Lucas want to buy?', 'A warm jacket', ['A book', 'Shoes', 'A bag']),
    choice('Who is the present for?', 'His sister', ['His brother', 'His mother', 'Himself']),
  ],
}];
const listening = [{
  text: 'Hi Tom! Are you coming to the match on Sunday? This time we are going by train instead of car. The train leaves at nine from platform two, so please do not be late again like last month.',
  items: [
    choice('When does the train leave?', 'At nine', ['At ten', 'At six', 'At five']),
    choice('Where does it leave from?', 'Platform two', ['Platform four', 'Platform one', 'Platform five']),
  ],
}, {
  text: 'I will wait for you at the cafe near the library after my class ends. If the weather is nice we can walk to the park and talk about the trip we planned last winter together.',
  items: [
    choice('Where will they meet?', 'At the cafe', ['At the gym', 'At school', 'At home']),
    choice('What can they do if the weather is nice?', 'Walk to the park', ['Swim', 'Drive home', 'Watch a film']),
  ],
}];

const server = createServer((request, response) => {
  if (request.method !== 'POST' || !(request.url ?? '').endsWith('/chat/completions')) {
    response.writeHead(404, { 'content-type': 'application/json' });
    return response.end(JSON.stringify({ error: { code: 'not_found' } }));
  }
  let body = '';
  request.on('data', chunk => { body += chunk; if (body.length > 1_048_576) request.destroy(); });
  request.on('end', () => {
    let prompt = '';
    try { const parsed = JSON.parse(body); prompt = parsed.messages?.map(m => m.content).join('\n') ?? ''; } catch { /* malformed → plain OK */ }
    const reply = content => JSON.stringify({
      id: 'stub-e2e', object: 'chat.completion', model: 'stub-model',
      choices: [{ index: 0, message: { role: 'assistant', content }, finish_reason: 'stop' }],
      usage: { prompt_tokens: 1, completion_tokens: 1, total_tokens: 2 },
    });
    response.writeHead(200, { 'content-type': 'application/json' });
    if (prompt.includes('Return ONLY JSON with exactly {"content":{"topic"')) {
      if (!dailyFixture) return response.end(reply('{}'));
      return response.end(reply(JSON.stringify(dailyCandidate())));
    }
    if (prompt.includes('independent strict language-learning content judge')) {
      let ids = [];
      try { ids = JSON.parse(prompt.slice(prompt.lastIndexOf('\n') + 1)).requiredEvidenceIds ?? []; } catch { /* invalid request */ }
      return response.end(reply(JSON.stringify({ decision: 'pass', evidence: ids.map(id => ({ id, verdict: 'pass',
        rationale: 'Deterministic fixture content passes browser-worker validation.' })) })));
    }
    if (prompt.includes('vocabulary and grammar')) return response.end(reply(JSON.stringify(recognition)));
    if (prompt.includes('reading passages')) return response.end(reply(JSON.stringify(reading)));
    if (prompt.includes('listening passages')) return response.end(reply(JSON.stringify(listening)));
    if (prompt.includes('production items')) return response.end(reply(JSON.stringify(writing)));
    if (prompt.includes('strict reviewer')) {
      // Judge pass: approve everything that reached the judge.
      let ids = [];
      try { ids = (JSON.parse(prompt.split('\n').pop() ?? '[]')).map(item => item.id); } catch { /* no items */ }
      return response.end(reply(JSON.stringify({ items: ids.map(id => ({ id, verdict: 'ok' })) })));
    }
    response.end(reply('OK'));
  });
});

server.listen(9999, () => console.info('stub LLM provider listening on 9999'));
