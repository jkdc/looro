import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { test } from 'node:test';
import { gradeDailyClosedAnswers, hashDailyArtifact, hashDailyText, projectPublicDailyPackage, validateDailyPackage } from '../src/daily-content.js';
import { dailyLanguageProfiles } from '../src/daily-language-profiles.js';

const fixture = JSON.parse(await readFile(new URL('../../../shared/contracts/daily-practice/fixtures/day-en.json', import.meta.url), 'utf8'));

test('same_package_for_different_learners', () => {
  const first = projectPublicDailyPackage({ ...fixture, learnerLevel: 'A1', history: ['private'] });
  const second = projectPublicDailyPackage({ ...fixture, learnerLevel: 'C1', history: ['different'] });
  assert.deepEqual(first, second);
});

test('english_disabled_profile_is_rejected', () => {
  assert.equal(dailyLanguageProfiles['en']?.enabled, true);
  assert.equal(dailyLanguageProfiles['es']?.enabled, false);
  const spanish = { ...fixture, language: 'es', profileVersion: 'es-v1' };
  assert.throws(() => validateDailyPackage(spanish), /binding/);
});

test('public_projection_contains_no_answer_keys', () => {
  const result = projectPublicDailyPackage({ ...fixture, acceptedAnswers: ['private'], judgeEvidence: 'private' });
  assert.equal(JSON.stringify(result).includes('acceptedAnswers'), false);
  assert.equal(JSON.stringify(result).includes('acceptedOptionId'), false);
  assert.equal(JSON.stringify(result).includes('judgeEvidence'), false);
});

test('jsonb_roundtrip_keeps_hash', () => {
  const roundTrip = JSON.parse(JSON.stringify({ ...fixture, estimatedMinutes: {
    total: 17, review: 2, practice: 3, speaking: 6, listening: 4, preparation: 2,
  } }));
  assert.equal(validateDailyPackage(roundTrip).contentHash, fixture.contentHash);
  assert.equal(hashDailyArtifact(roundTrip), fixture.contentHash);
  assert.equal(hashDailyArtifact({ b: 2, a: 1 }), hashDailyArtifact({ a: 1, b: 2 }));
  assert.equal(hashDailyText('abc'), 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad');
});

test('duplicate_question_or_option_id_is_rejected', () => {
  const duplicateQuestion = structuredClone(fixture);
  duplicateQuestion.listening.questions[1].id = duplicateQuestion.listening.questions[0].id;
  assert.throws(() => validateDailyPackage(duplicateQuestion), /duplicate/i);
  const duplicateOption = structuredClone(fixture);
  duplicateOption.practice.questions[0].options[1].id = duplicateOption.practice.questions[0].options[0].id;
  assert.throws(() => validateDailyPackage(duplicateOption), /duplicate/i);
});

test('english_contraction_is_not_stripped', () => {
  assert.equal(gradeDailyClosedAnswers("don't", ["don't"]), true);
  assert.equal(gradeDailyClosedAnswers('dont', ["don't"]), false);
});

test('daily_package_binds_local_speech_locale_without_audio_asset', () => {
  const packageWithoutAudio = structuredClone(fixture);
  delete packageWithoutAudio.listening.audio;
  packageWithoutAudio.listening.speechLocale = 'en-US';
  assert.equal(validateDailyPackage(packageWithoutAudio).listening.speechLocale, dailyLanguageProfiles.en!.speechLocale);
  assert.throws(() => validateDailyPackage({ ...packageWithoutAudio,
    listening: { ...packageWithoutAudio.listening, speechLocale: 'en-GB' } }), /binding/i);
  assert.throws(() => validateDailyPackage({ ...packageWithoutAudio,
    listening: { ...packageWithoutAudio.listening, audio: fixture.listening.audio } }), /listening/i);
});
