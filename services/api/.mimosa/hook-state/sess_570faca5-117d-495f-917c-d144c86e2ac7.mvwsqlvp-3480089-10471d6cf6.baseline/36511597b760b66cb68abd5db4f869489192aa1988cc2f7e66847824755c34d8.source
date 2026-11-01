import { resolveExamBlueprint } from '../src/level-test-blueprint.js';
import type { ExamSectionName } from '../src/level-test-blueprint.js';

/** Synthetic transport labels exercise plumbing, not pedagogical correctness. */
export function syntheticSkill(section:ExamSectionName, index:number, level='A1'):string {
  const ids=resolveExamBlueprint('en',level)!.assessment!.sections[section].requiredSkills;
  return ids[index%ids.length]!;
}
export function syntheticJudgeSkill(item:{assessedSkill?:string}) {
  return {assessedSkill:item.assessedSkill,skillEvidence:'Synthetic fixture: the assigned primary skill is exercised by this test item.'};
}
