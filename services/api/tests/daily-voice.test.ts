import assert from 'node:assert/strict';
import { test } from 'node:test';
import { buildDailyVoiceInstructions, canStartDailySpeaking, dailySpeechHistory } from '../src/daily-voice.js';

test('daily speaking instructions create a topic conversation with user-owned opening and close',()=>{
  const instructions=buildDailyVoiceInstructions({language:'en',topic:{title:'Small acts of kindness',summary:'How everyday help changes a community.'},
    speaking:{role:'A friendly neighbor sharing a recent experience.',opening:'Tell me about a small kind thing you noticed.',topicAnchors:['A helpful moment','How it felt'],
      closingCue:'When you are ready, say that you would like to finish.'},goals:[{task:'Describe one example and explain why it mattered.'}]});
  assert.match(instructions,/Small acts of kindness/);
  assert.match(instructions,/friendly neighbor/);
  assert.match(instructions,/Tell me about a small kind thing/);
  assert.match(instructions,/say that you would like to finish/);
  assert.match(instructions,/Do not correct mistakes.*during the conversation/i);
  assert.doesNotMatch(instructions,/personal history|previous weakness/i);
});

test('speaking context is unavailable until listening resolves and the user closes speaking',()=>{
  assert.equal(canStartDailySpeaking({stage:'listening',completedStages:[],speakingClosed:false}),false);
  assert.equal(canStartDailySpeaking({stage:'speaking',completedStages:[],speakingClosed:false}),false);
  assert.equal(canStartDailySpeaking({stage:'speaking',completedStages:[{stage:'listening',status:'skipped'}],speakingClosed:false}),true);
  assert.equal(canStartDailySpeaking({stage:'speaking',completedStages:[{stage:'listening',status:'completed'}],speakingClosed:true}),false);
});

test('reconnected speaking context restores accepted turns in sequence without clipping messages',()=>{
  const history=dailySpeechHistory({fragments:[
    {role:'tutor',text:'Response',sequence:1},{role:'learner',text:'A useful first turn.',sequence:0},
    {role:'learner',text:'x'.repeat(1000),sequence:2}
  ]});
  assert.deepEqual(history.map(item=>item.role),['user','assistant','user']);
  assert.equal(history[0]!.content[0]!.text,'A useful first turn.');
  assert.equal(history[2]!.content[0]!.text,'x'.repeat(1000));
});

test('reconnected turns use voice segment order when fragment sequence resets per segment',()=>{
  const history=dailySpeechHistory({fragments:[
    {role:'learner',text:'L1',voiceSegmentId:'s1',sequence:0},{role:'tutor',text:'T1',voiceSegmentId:'s1',sequence:1},
    {role:'learner',text:'L2',voiceSegmentId:'s2',sequence:0},{role:'tutor',text:'T2',voiceSegmentId:'s2',sequence:1}
  ]},new Map([['s1',0],['s2',1]]));
  assert.deepEqual(history.map(item=>item.content[0]!.text),['L1','T1','L2','T2']);
});
