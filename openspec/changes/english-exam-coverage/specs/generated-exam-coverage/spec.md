# English generated-exam capability coverage

## Requirement: Versioned automated pedagogical evidence

English A1–C1 SHALL use a versioned assessment design linked to catalog IDs, with a separate recorded LLM review of its exact canonical payload. Automated evidence SHALL NOT be described as teacher approval, empirical calibration or CEFR certification. Unreviewed or stale references SHALL NOT activate through the exporter.

### Scenario: Complete reference examples
- Each enabled level/section has a reviewed reference with accepted answers and observable support.
- Reading/listening references contain the full passage and all required questions, not orphan passage IDs.
- Non-English models remain unchanged.

## Requirement: Assessed capability is an explicit semantic claim

Each item governed by the new design SHALL retain one permitted primary `assessedSkill` internally. Missing, unknown, wrong-section or exposure-only tags SHALL NOT satisfy coverage. The existing judge SHALL check that answering actually requires the claimed skill and return matching bounded observable skill evidence, in addition to the language/key evidence.

### Scenario: Incomplete or mismatched evidence
- An item cannot publish with missing, oversized or mismatched semantic evidence.
- Rejected or uncertain items cannot silently satisfy coverage after filtering.

## Requirement: Every form satisfies the frozen coverage contract

The deterministic composer SHALL satisfy required capability presence jointly with existing item-kind quotas and whole-passage grouping. Search SHALL be bounded, with distinct explicit capacity and exhaustion errors; constraints SHALL NOT be silently relaxed.

### Scenario: Rehashed but uncovered form
- Candidate-pool coverage alone is insufficient.
- Automatic and manual publication validate the selected items of every form against the frozen matrix.
- Recomputing a snapshot hash does not authorize a form lacking coverage.

## Requirement: Compatibility and honest verification

Learner snapshots SHALL exclude internal assessed metadata. Current matrix changes SHALL NOT reinterpret frozen drafts. Historical complete frozen contracts without assessment metadata SHALL retain their original evidence schema. Published snapshots and attempts SHALL NOT be rewritten.

Verification SHALL include isolated PostgreSQL without DB skips, types, exporters, cross-platform contracts and independent review. A real authorized learner pilot and production deployment remain separate from this local implementation.
