-- Pronunciation drills join the progress ledger as a first-class kind: the
-- in-lesson drill records one mark per passed target exactly like listenings
-- do. Rows are never rewritten (the merge is monotonic), so this is a
-- metadata-only constraint rebuild.
ALTER TABLE user_progress DROP CONSTRAINT user_progress_kind_check;
ALTER TABLE user_progress ADD CONSTRAINT user_progress_kind_check
  CHECK (kind IN ('lesson','conversation_theme','exercise_set','listening',
    'reading','pronunciation','recall_round','words_practice','assessment'));
