# Admin operations requirements

## Effective configuration
The panel MUST show effective provider, model, base URL, masked key hint, and publication/verification/variant settings from the server. Saving and reloading MUST show the values generation will use, not unrelated environment defaults. Secret values MUST never be returned or rendered.
### Scenario: Save and reload
- GIVEN an authenticated administrator and valid editable settings
- WHEN the administrator saves and reloads
- THEN the display matches effective server settings and keeps the key masked

## Saved-configuration connection test
The panel MUST identify the test as applying to saved settings. If form edits differ from loaded state, it MUST require save or warn that unsaved edits are not tested. The API MUST bound duration and response bytes and redact secrets, response bodies, and raw provider errors.
### Scenario: Unsaved edits
- GIVEN an edited but unsaved setting
- WHEN the administrator invokes the test
- THEN the UI requires saving or states that only saved settings are tested

## Private, audited draft moderation
The admin API MUST list drafts and judge verdicts with bounded pagination. Only authorized same-origin administrators may read or moderate. Approval and rejection MUST be audited, publication MUST use existing policy, and drafts MUST NOT expose student attempt history.
### Scenario: Moderate a draft
- GIVEN an unpublished generated draft
- WHEN an authorized administrator approves or rejects it
- THEN the action is audited and only approval can publish it

## Accurate generation status
The UI MUST show safe latest-job status, time, and error fields and distinguish scheduler configuration from process activity. It MUST NOT disclose secrets, prompts, provider bodies, or student history.
### Scenario: Configured but inactive
- GIVEN generation is configured but this process has no active scheduler
- WHEN status is displayed
- THEN the UI does not equate configuration with a running scheduler

## Existing admin protection
All new admin reads and writes MUST use existing authorization, same-origin cookie behavior, and Origin enforcement. The UI MUST NOT require an unsupported CSRF header.
### Scenario: Unauthorized or cross-origin request
- GIVEN missing admin authorization or a rejected Origin
- WHEN any T11 operation is requested
- THEN it is rejected without disclosing protected data or changing state

## Isolated browser verification
A Docker-run browser test MUST exercise the rendered panel against isolated services with stub provider traffic and no real LLM call.
### Scenario: Complete isolated panel flow
- GIVEN an isolated admin API/database and stub provider
- WHEN the browser saves settings, tests, moderates a draft, and views generation status
- THEN rendered and server state meet the requirements without external provider access
