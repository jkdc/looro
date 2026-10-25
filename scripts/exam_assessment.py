"""Validate LLM-reviewed assessment references before embedding them in the API."""
import hashlib
import json


def embed_assessment(root, models):
    design = json.loads((root / 'shared/leveltests/en-assessment-design.json').read_text())
    catalog = {s['id'] for s in json.loads((root / 'shared/skills/skill-catalog.json').read_text())['skills']}

    def need(condition, message):
        if not condition:
            raise SystemExit('English assessment: ' + message)

    need(design.get('schemaVersion') == 1, 'unsupported schema')
    need(type(design.get('revision')) is int and design['revision'] > 0, 'invalid revision')
    need(design.get('rubricVersion') == 1, 'unsupported rubric')
    review = design.get('review', {})
    digest = hashlib.sha256(json.dumps({k: v for k, v in design.items() if k != 'review'},
        sort_keys=True, ensure_ascii=False, separators=(',', ':')).encode()).hexdigest()
    need(review.get('status') == 'approved' and review.get('kind') == 'automated-llm', 'automated approval required')
    need(review.get('contentHash') == digest, 'review does not cover these exact bytes')
    need(review.get('rubricVersion') == design['rubricVersion'] and isinstance(review.get('model'), str)
         and review['model'].strip(), 'review provenance required')
    need(isinstance(review.get('evidence'), str) and 8 <= len(review['evidence']) <= 1000, 'review evidence required')
    levels = design.get('levels', [])
    english = {m['level']: m for m in models['levels'] if m['languageID'] == 'en'}
    need(len(levels) == len(english) and {e.get('level') for e in levels} == set(english), 'enabled levels must match')
    for level in levels:
        model = english[level['level']]
        sections = level.get('sections', {})
        need(set(sections) == {'recognition', 'reading', 'listening', 'writing'}, 'all four sections required')
        for section, rules in sections.items():
            required = rules.get('requiredSkills', [])
            shape = model['examShape']
            capacity = shape[section] if section in ('recognition', 'writing') else shape[section + 'Passages'] * shape[section + 'PerPassage']
            need(isinstance(required, list) and 0 < len(required) <= min(capacity, 12)
                 and all(isinstance(s, str) and s in catalog for s in required)
                 and len(set(required)) == len(required), f'{level["level"]}/{section}: invalid or infeasible catalog coverage')
            guidance = rules.get('guidance', {})
            need(isinstance(guidance, dict) and all(key in required and isinstance(value, str)
                 and 8 <= len(value) <= 1000 for key, value in guidance.items()), 'invalid skill guidance')
            example = rules.get('example', {})
            need(isinstance(example.get('id'), str) and example['id'], 'reference identity required')
            items = example.get('items', [])
            comprehension = section in ('reading', 'listening')
            need(isinstance(items, list) and len(items) == (shape[section + 'PerPassage'] if comprehension else 1), 'complete reference questions required')
            if comprehension:
                passage = example.get('passage', {})
                text = passage.get('text', '')
                low, high = model['sections'][section]['words']
                need(isinstance(text, str) and low <= len(text.split()) <= high, 'reference passage length')
                need(isinstance(passage.get('title'), str) and passage['title'], 'reference passage title')
            for item in items:
                need(item.get('assessedSkill') in required, 'reference has unapproved assessed skill')
                need(isinstance(item.get('prompt'), str) and 0 < len(item['prompt']) <= 500, 'reference prompt required')
                need(all(isinstance(item.get(key), str) and 8 <= len(item[key]) <= 240 for key in ('explain', 'evidence')), 'reference key support required')
                kind, answers = item.get('kind'), item.get('answers')
                need(kind in (('choice',) if comprehension else ('gap', 'written', 'order') if section == 'writing' else ('choice', 'gap', 'order')), 'reference kind')
                need(isinstance(answers, list) and 0 < len(answers) <= 4 and all(isinstance(a, str) and a.strip() for a in answers), 'reference answers')
                if kind == 'choice':
                    options = item.get('options', [])
                    need(isinstance(options, list) and len(options) == 4 and all(isinstance(o, str) and o.strip() for o in options)
                         and len(set(options)) == 4 and len(answers) == 1 and answers[0] in options, 'reference options/key')
                else:
                    need(not item.get('options'), 'production cannot have options')
                    need(kind != 'order' or len(answers) == 1 and len(answers[0].split()) >= 3, 'reference order answer')
        model['assessment'] = {'revision': design['revision'], 'rubricVersion': design['rubricVersion'],
            'review': review, 'catalogIDs': sorted({s for rule in sections.values() for s in rule['requiredSkills']}), 'sections': sections}
    return models
