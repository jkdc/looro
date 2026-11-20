import type { ExamModelEntry, ExamSectionName } from './level-test-blueprint.js';
import type { GeneratedPool, ValidationFailure } from './level-tests-validate.js';

export interface AssessmentExample {
  id: string;
  passage?: {title: string; text: string};
  items: {kind: string; prompt: string; options?: string[]; answers: string[];
    explain: string; assessedSkill: string; evidence: string}[];
}
export interface AssessmentContract {
  revision: number;
  rubricVersion: 1;
  review: {status: 'approved'; kind: 'automated-llm'; model: string; contentHash: string; rubricVersion: number; evidence: string};
  catalogIDs: string[];
  sections: Record<ExamSectionName, {requiredSkills: string[]; guidance?: Record<string,string>; example: AssessmentExample}>;
}
const sections = ['recognition','writing','reading','listening'] as const;

/** Validate frozen metadata against its own catalog, never today's mutable catalog. */
export function validAssessmentContract(value: unknown, model: ExamModelEntry): value is AssessmentContract {
  if (!value || typeof value !== 'object') return false;
  const a = value as AssessmentContract;
  if (!Number.isSafeInteger(a.revision) || a.revision < 1 || a.rubricVersion !== 1 ||
      a.review?.status !== 'approved' || a.review.kind !== 'automated-llm' || a.review.rubricVersion !== 1 ||
      typeof a.review.model !== 'string' || !a.review.model.trim() ||
      !/^[a-f0-9]{64}$/.test(a.review.contentHash) || typeof a.review.evidence !== 'string' ||
      a.review.evidence.trim().length < 8 || !Array.isArray(a.catalogIDs) ||
      !a.catalogIDs.every(id=>typeof id==='string' && id.length>2) || new Set(a.catalogIDs).size!==a.catalogIDs.length) return false;
  return sections.every(section=> {
    const rule=a.sections?.[section];
    const capacity=section==='reading'||section==='listening'
      ? model.examShape[`${section}Passages`]*model.examShape[`${section}PerPassage`] : model.examShape[section];
    return !!rule && Array.isArray(rule.requiredSkills) && rule.requiredSkills.length>0 &&
      rule.requiredSkills.length<=Math.min(capacity,12) && new Set(rule.requiredSkills).size===rule.requiredSkills.length &&
      rule.requiredSkills.every(id=>a.catalogIDs.includes(id)) && !!rule.example && Array.isArray(rule.example.items) &&
      rule.example.items.length===(section==='reading'||section==='listening'?model.examShape[`${section}PerPassage`]:1) &&
      (!(section==='reading'||section==='listening') || typeof rule.example.passage?.text==='string') &&
      (!rule.guidance || Object.entries(rule.guidance).every(([id,text])=>rule.requiredSkills.includes(id)&&typeof text==='string'));
  });
}

export function assessmentPoolErrors(pool: GeneratedPool, model: ExamModelEntry): ValidationFailure[] {
  if (!model.assessment) return [];
  if (!validAssessmentContract(model.assessment,model)) return [{section:'recognition',code:'assessment_contract',detail:'invalid frozen assessment contract'}];
  const errors: ValidationFailure[]=[];
  for (const section of sections) {
    const rules=model.assessment.sections[section];
    const groups=section==='reading'||section==='listening'?pool[`${section}Passages`].map(p=>p.items):[pool[section]];
    const items=groups.flat();
    if (items.some(item=>typeof item.assessedSkill!=='string'||!rules.requiredSkills.includes(item.assessedSkill)))
      errors.push({section,code:'assessed_skill',detail:'each item needs one primary assessedSkill allowed by this section'});
    if (rules.requiredSkills.some(id=>!items.some(item=>item.assessedSkill===id)))
      errors.push({section,code:'coverage',detail:'candidate pool does not cover every required skill'});
    if ((section==='reading'||section==='listening') && groups.some(items=>items.length!==model.examShape[`${section}PerPassage`]))
      errors.push({section,code:'passage_questions',detail:'covered passage groups must have the exact frozen question count'});
  }
  return errors;
}

export function assessmentFormFailure(variants: readonly {items: readonly {id:string;section:string}[]}[], pool: GeneratedPool, model: ExamModelEntry): boolean {
  if (!model.assessment) return false;
  const all=[...pool.recognition,...pool.writing,...pool.readingPassages.flatMap(p=>p.items),...pool.listeningPassages.flatMap(p=>p.items)];
  const byID=new Map(all.map(item=>[item.id,item]));
  return variants.some(form=>sections.some(section=>model.assessment!.sections[section].requiredSkills.some(skill=>
    !form.items.some(item=>item.section===section&&byID.get(item.id)?.section===section&&byID.get(item.id)?.assessedSkill===skill))));
}

/** Bounded 0/1 dynamic programming: whole groups, skill masks and kind quotas are joint constraints. */
export function selectCoverageGroups<T>(groups: readonly {value:T;skills:readonly string[];kind?:string;key?:string}[], count:number,
  required:readonly string[], quotas?:Record<string,number>, stateLimit=100_000, excludedSets:readonly ReadonlySet<string>[]= []): T[] | undefined {
  if (required.length>12 || count<0 || !Number.isSafeInteger(count)) throw new Error('invalid_coverage_capacity');
  if (excludedSets.length>5 || excludedSets.some(set=>set.size!==count) ||
      excludedSets.length && groups.some(group=>typeof group.key!=='string')) throw new Error('invalid_coverage_diversity');
  const fullNovelty=(1<<excludedSets.length)-1;
  const kinds=Object.keys(quotas??{}).sort();
  const full=(1<<required.length)-1;
  type State={count:number;mask:number;novelty:number;kinds:number[];previous?:State;index?:number};
  let states=new Map<string,State>([['0:0:0:'+kinds.map(()=>0).join(','),{count:0,mask:0,novelty:0,kinds:kinds.map(()=>0)}]]);
  let work=0;
  for (let index=0;index<groups.length;index++) {
    const group=groups[index]!;
    const mask=group.skills.reduce((mask,skill)=>{const bit=required.indexOf(skill);return bit<0?mask:mask|(1<<bit);},0);
    // Equal group counts differ from an earlier set iff at least one new key is selected.
    // Keep novelty in the DP state so equivalent coverage paths cannot erase that alternative.
    const novelty=excludedSets.reduce((mask,set,index)=>set.has(group.key!)?mask:mask|(1<<index),0);
    const next=new Map(states);
    for (const state of states.values()) {
      if (++work>stateLimit) throw new Error('coverage_search_limit');
      if (state.count>=count) continue;
      const counts=[...state.kinds];
      if (quotas) {
        const kind=kinds.indexOf(group.kind??'');
        if (kind<0 || counts[kind]! >= quotas[kinds[kind]!]!) continue;
        counts[kind]!++;
      }
      const candidate:State={count:state.count+1,mask:state.mask|mask,novelty:state.novelty|novelty,kinds:counts,previous:state,index};
      const key=`${candidate.count}:${candidate.mask}:${candidate.novelty}:${counts.join(',')}`;
      if (!next.has(key)) next.set(key,candidate);
      if (candidate.count===count && candidate.mask===full && candidate.novelty===fullNovelty && (!quotas||kinds.every((kind,i)=>counts[i]===quotas[kind]))) {
        const selected:T[]=[];
        for (let node:State|undefined=candidate;node?.index!==undefined;node=node.previous) selected.push(groups[node.index]!.value);
        return selected.reverse();
      }
    }
    states=next;
  }
  return count===0&&full===0?[]:undefined;
}

/** Runtime rubric v1 is part of the frozen assessment, not an editor-only skill. */
export function assessmentGenerationRubric(model:ExamModelEntry, section:ExamSectionName, repairing = false): string {
  const a=model.assessment;
  if (!a) return '';
  const rules=a.sections[section];
  const coverage = repairing ? 'Cover the missing primary skills requested for this repair.'
    : 'Cover every listed skill in the candidate pool, with alternatives across passages and kinds so multiple forms can retain coverage.';
  return `Assessment rubric v${a.rubricVersion}, design revision ${a.revision}. Every item must include exactly one assessedSkill from ${JSON.stringify(rules.requiredSkills)}. ${coverage} The tag identifies what answering actually requires, not vocabulary merely encountered. ${JSON.stringify(rules.guidance??{})}\nReviewed complete reference (style and task-demand anchor; do not copy): ${JSON.stringify(rules.example)}`;
}
