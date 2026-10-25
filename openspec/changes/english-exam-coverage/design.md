# Design

## Canonical assessment contract

`shared/leveltests/en-assessment-design.json` owns the English matrix and twenty small reference examples (one per level/section), not a learner bank. A separate LLM review records its rubric, model, exact canonical content hash, verdict and findings. The exporter validates it against the skill catalog and current form capacities, then embeds the relevant level under `ExamModelEntry.assessment`. The complete existing frozen blueprint persists this contract without a new database table or migration. Historical models without this optional field retain their original path.

Each section lists permitted/required primary assessed skill IDs. Require at least one item per listed capability in each form; this is a pilot coverage rule, not a validated psychometric quota. Keep existing kind mix and lengths. Add catalog IDs for C1 inversion/cleft sentences/ellipsis because mislabeling them as unrelated existing IDs would defeat coverage.

## Roles and trust boundary

Versioned runtime rubrics define matrix design (infrequent editorial use), existing item generation and existing separate judge review. Generation returns one catalog ID `assessedSkill` per item. Normalize and retain it in the internal pool only. Reject missing, unknown or wrong-section tags in new covered models; exposure-only labels never count. The judge receives the tag and approved coverage context and must return the same tag with short observable `skillEvidence` establishing that the task actually assesses it, independently of language/key evidence. Do not request hidden reasoning or call an automated review teacher approval.

## Composition and publication

Select whole comprehension groups and individual recognition/writing items with a bounded deterministic dynamic program that satisfies required capability presence and existing kind quotas together. Every candidate in the selected comprehension group is included, so covered models enforce the requested questions-per-passage count exactly. Preserve current diversity checks; if no capacity exists, fail explicitly instead of substituting duplicates or reducing coverage. Limit state growth and report exhaustion distinctly. Track a novelty mask against previously selected passage sets inside the DP, so coverage-equivalent paths do not erase available distinct forms. If no new set exists, a bounded diagnostic fallback preserves the shared final gate’s existing item/stimulus-diversity errors; it cannot authorize duplicates.

Keep public snapshots unchanged by projecting tags out before hashing/persisting variants. Final auto/manual checks resolve selected IDs back to the internal pool and verify per-form coverage and matching semantic judge evidence against the frozen assessment. Non-English and old English frozen contracts do not acquire new tag requirements retroactively.

## Evidence and external boundaries

Use Superpowers TDD and a separate pedagogical LLM review plus final independent code review. Full PostgreSQL integration runs in the existing ephemeral Docker stack, never a real environment. Do not run actual configured providers or deploy. An LLM review is not empirical timing/difficulty evidence; authorized learner pilots remain external.
