import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { test } from 'node:test';
import { validateDailyAnswerKeys } from '../src/daily-generation.js';

const dailyPackage = JSON.parse(await readFile(new URL('../../../shared/contracts/daily-practice/fixtures/day-en.json', import.meta.url), 'utf8'));
const questions = [...dailyPackage.listening.questions, ...dailyPackage.practice.questions];
const keyFor = (question: any) => ({ questionId: question.id,
  ...(question.kind === 'single_choice' ? { acceptedOptionId: question.options[0].id } : { acceptedAnswers: ['a clear answer'] }),
  explanation: { en: 'This answer follows the passage and topic.', es: 'Esta respuesta sigue el texto y el tema.' },
  targetExpressionIds: [dailyPackage.expressions[0].id] });
const validKeys = () => ({
  listening: dailyPackage.listening.questions.map(keyFor),
  practice: dailyPackage.practice.questions.map(keyFor),
});

test('daily_answer_keys_bind_every_public_question_and_expression', () => {
  assert.deepEqual(validateDailyAnswerKeys(dailyPackage, validKeys()), validKeys());
});

test('daily_answer_keys_reject_missing_extra_and_duplicate_question_bindings', () => {
  const missing = validKeys(); missing.practice.pop();
  assert.throws(() => validateDailyAnswerKeys(dailyPackage, missing), /invalid_daily_answer_keys/);
  const extra = validKeys(); extra.listening.push(keyFor(questions[0]));
  assert.throws(() => validateDailyAnswerKeys(dailyPackage, extra), /invalid_daily_answer_keys/);
  const duplicate = validKeys(); duplicate.practice[1] = { ...duplicate.practice[1], questionId: duplicate.practice[0].questionId };
  assert.throws(() => validateDailyAnswerKeys(dailyPackage, duplicate), /invalid_daily_answer_keys/);
});

test('daily_answer_keys_reject_wrong_choice_key_or_unknown_expression', () => {
  const wrongChoice = validKeys(); wrongChoice.listening[0] = { ...wrongChoice.listening[0], acceptedOptionId: 'not-an-option' };
  assert.throws(() => validateDailyAnswerKeys(dailyPackage, wrongChoice), /invalid_daily_answer_keys/);
  const unknownExpression = validKeys(); unknownExpression.practice[0] = { ...unknownExpression.practice[0], targetExpressionIds: ['missing'] };
  assert.throws(() => validateDailyAnswerKeys(dailyPackage, unknownExpression), /invalid_daily_answer_keys/);
});
