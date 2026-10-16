# Level-test attempt event sync

`POST /v1/level-test-attempts/sync` creates an account-owned attempt and its
immutable snapshot. Subsequent local outbox records are sent one at a time to
`POST /v1/level-test-attempts/events` with the same bearer-authenticated owner.
The account ID is derived from the token; it is never accepted from the payload.

```json
{
  "events": [{
    "eventId": "11111111-1111-4111-8111-111111111111",
    "attemptId": "22222222-2222-4222-8222-222222222222",
    "baseRevision": 1,
    "revision": 2,
    "type": "progress",
    "payload": {
      "questionId": "question-1",
      "response": { "value": "answer" },
      "position": 1,
      "updatedAt": "2026-10-07T12:00:00.000Z"
    }
  }]
}
```

The event ID is an idempotency key scoped to the account. A retry with the same
canonical event returns the exact stored ACK; reusing that ID for different
content returns `409 exam_event_conflict`. The attempt update and ACK ledger row
commit together. The event's base revision must equal the current server revision
and its revision must be the next integer; otherwise the API returns `409
exam_attempt_conflict` with the confirmed server attempt and applies no change.
Clients retain their local event and must not silently merge or overwrite it.

Supported events are `progress`, `completed`, and explicit `takeover`. A takeover
is an empty state transition carrying only `updatedAt`, guarded by the current
confirmed `baseRevision`; it does not alter answers or results. Completion stores
the original result and rejects every later event. To preserve a divergent local
branch as a separate attempt, create a new attempt ID and upload that full attempt
through the initial `/sync` endpoint; never reset or mutate the conflicted attempt.

Each request contains exactly one event, is limited by the route's 256 KB body
bound, and returns one ACK only after its transaction commits. Restore continues
through the authenticated paginated attempt-history routes. Operational logs must
not include event payloads, answers, results, or bearer tokens.


## Initial creation receipt

The first accepted upload for an account-owned attempt atomically stores an
immutable receipt containing a SHA-256 fingerprint of the canonical attempt
payload and its snapshot identity/metadata, plus the original creation ACK.
Repeating the same creation request returns that ACK even after later events
advance the mutable attempt. Reusing the owner-scoped attempt ID with different
creation data returns 409 and does not overwrite either record. Receipt rows
cascade with their attempt when the account is deleted. Their contents are not
returned by history APIs and must not be logged.
