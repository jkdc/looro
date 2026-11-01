import { test } from 'node:test';
import assert from 'node:assert/strict';
import { selectCoverageGroups, assessmentPoolErrors, assessmentFormFailure } from '../src/level-test-assessment.js';
import { resolveExamBlueprint } from '../src/level-test-blueprint.js';
import { buildSectionMessages } from '../src/level-tests-llm.js';

test('coverage selection satisfies kind quotas jointly, not with a greedy prefix', () => {
  const groups = [{value:'a',skills:['x'],kind:'choice'}, {value:'b',skills:['y'],kind:'choice'},
    {value:'c',skills:['x'],kind:'gap'}, {value:'d',skills:['x'],kind:'choice'}];
  assert.deepEqual(selectCoverageGroups(groups, 2, ['x','y'], {choice:1,gap:1}), ['b','c']);
  assert.equal(selectCoverageGroups(groups, 1, ['x','y']), undefined);
  assert.throws(() => selectCoverageGroups(groups, 2, ['x','y'], undefined, 1), /coverage_search_limit/);
});

test('whole passage groups and ordering are deterministic', () => {
  const groups = [{value:'p1',skills:['detail','gist']}, {value:'p2',skills:['detail']}, {value:'p3',skills:['inference']}];
  assert.deepEqual(selectCoverageGroups(groups, 2, ['detail','gist','inference']), ['p1','p3']);
  assert.deepEqual(selectCoverageGroups(groups, 2, ['detail','gist','inference']), ['p1','p3']);
});

test('all English levels load approved full references into actual generation messages', () => {
  for (const level of ['A1','A2','B1','B2','C1']) {
    const model = resolveExamBlueprint('en', level)!;
    assert.equal(model.assessment?.review.status, 'approved');
    for (const section of ['recognition','writing','reading','listening'] as const) {
      const rules = model.assessment!.sections[section];
      const messages = buildSectionMessages(section,'en',level,10,3,1,[],model);
      const text = messages.map(x=>x.content).join('\n');
      assert.ok(text.includes('assessedSkill'));
      assert.ok(text.includes(JSON.stringify(rules.example)));
      for (const id of rules.requiredSkills) assert.ok(text.includes(id));
      if (section==='reading'||section==='listening') assert.ok(rules.example.passage!.text.length > 100);
    }
  }
});

test('missing, unknown, cross-section or exposure-only tags cannot satisfy the matrix', () => {
  const model = resolveExamBlueprint('en','A1')!;
  const pool = {recognition:[{id:'r',section:'recognition' as const,kind:'gap',prompt:'x',answers:['a'],options:[],explain:'explanation',skill:model.assessment!.sections.recognition.requiredSkills[0]}], writing:[],readingPassages:[],listeningPassages:[]};
  assert.ok(assessmentPoolErrors(pool,model).some(e=>e.code==='assessed_skill'));
  for (const tag of ['not:catalog','reading:gist']) {
    Object.assign(pool.recognition[0]!,{assessedSkill:tag});
    assert.ok(assessmentPoolErrors(pool,model).some(e=>e.code==='assessed_skill'));
  }
  assert.equal(assessmentFormFailure([{items:[{id:'r',section:'recognition'}]}],pool,model),true);
  const legacy = {...model,assessment:undefined};
  assert.deepEqual(assessmentPoolErrors(pool,legacy),[]);
  assert.equal(assessmentFormFailure([{items:[]}],pool,legacy),false);
});

test('all levels compose complete covered deterministic private-metadata-free forms',async()=>{
  const {composeVariants}=await import('../src/level-tests-llm.js');
  const {syntheticSkill}=await import('./assessment-fixtures.js');
  for (const level of ['A1','A2','B1','B2','C1']) {
    const model=resolveExamBlueprint('en',level)!;
    const item=(section:'recognition'|'writing'|'reading'|'listening',index:number,id:string,kind='choice')=>({
      id,section,kind,assessedSkill:syntheticSkill(section,index,level),prompt:`Question ${id}`,options:['yes','no','maybe','never'],answers:['yes'],explain:'Synthetic fixture only.'});
    const mix=model.sections.recognition!.mix!;
    const choices=mix.choice??0;
    const pool={recognition:Array.from({length:model.poolTarget.recognition},(_,i)=>item('recognition',i,`r${i}`,i<choices?'choice':'gap')),
      writing:Array.from({length:model.poolTarget.writing},(_,i)=>item('writing',i,`w${i}`,'gap')),
      readingPassages:Array.from({length:model.poolTarget.readingPassages},(_,p)=>({title:`R${p}`,text:`Reading ${p}`,items:Array.from({length:model.examShape.readingPerPassage},(_,i)=>item('reading',i,`r${p}-${i}`))})),
      listeningPassages:Array.from({length:model.poolTarget.listeningPassages},(_,p)=>({title:`L${p}`,text:`Listening ${p}`,items:Array.from({length:model.examShape.listeningPerPassage},(_,i)=>item('listening',i,`l${p}-${i}`))}))};
    assert.deepEqual(assessmentPoolErrors(pool,model),[]);
    const variants=composeVariants(pool,model,32,3);
    assert.equal(assessmentFormFailure(variants,pool,model),false);
    assert.deepEqual(variants,composeVariants(pool,model,32,3));
    assert.ok(variants.every(v=>v.items.every(item=>!Object.hasOwn(item,'assessedSkill'))));
    const {englishServedFormFailure}=await import('../src/level-tests-validate.js');
    assert.equal(englishServedFormFailure(variants,model,pool),null);
    const missing=structuredClone(pool);missing.recognition.forEach(item=>item.assessedSkill=model.assessment!.sections.recognition.requiredSkills[0]!);
    assert.throws(()=>composeVariants(missing,model,32,3),/insufficient_coverage_capacity/);
  }
});

test('uneven passage coverage still selects distinct feasible variants',async()=>{
  const {composeVariants}=await import('../src/level-tests-llm.js');
  const {englishServedFormFailure}=await import('../src/level-tests-validate.js');
  const {syntheticSkill}=await import('./assessment-fixtures.js');
  const model=resolveExamBlueprint('en','A1')!;
  const item=(section:'recognition'|'writing'|'reading'|'listening',i:number,id:string,kind='choice')=>({
    id,section,kind,assessedSkill:syntheticSkill(section,i),prompt:`Question ${id}`,options:['one','two','three','four'],answers:['one'],explain:'Synthetic fixture only.'});
  const pool={recognition:Array.from({length:24},(_,i)=>item('recognition',i,`r${i}`,i<16?'choice':'gap')),
    writing:Array.from({length:8},(_,i)=>item('writing',i,`w${i}`,'gap')),
    readingPassages:Array.from({length:6},(_,p)=>({title:`R${p}`,text:`Reading ${p}`,items:Array.from({length:3},(_,i)=>item('reading',i,`r${p}-${i}`))})),
    listeningPassages:Array.from({length:6},(_,p)=>({title:`L${p}`,text:`Listening ${p}`,items:Array.from({length:3},(_,i)=>({...item('listening',i,`l${p}-${i}`),
      assessedSkill:p===2&&i===0?'listening:gist':p===4&&i===0?'listening:number-time':'listening:detail'}))}))};
  assert.deepEqual(assessmentPoolErrors(pool,model),[]);
  const forms=composeVariants(pool,model,32,3);
  assert.equal(englishServedFormFailure(forms,model,pool),null);
  assert.equal(new Set(forms.map(form=>[...form.listeningPassageIds].sort().join(','))).size,3);
  assert.equal(assessmentFormFailure(forms,pool,model),false);
  assert.deepEqual(forms,composeVariants(pool,model,32,3));
});
