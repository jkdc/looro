import XCTest

final class AppUITests: XCTestCase {
    override func setUpWithError() throws { continueAfterFailure = false }

    private func reveal(_ element: XCUIElement, in app: XCUIApplication) {
        for _ in 0..<8 {
            let footer = app.buttons["onboarding-continue"].frame
            if element.isHittable && element.frame.minY >= 110 && element.frame.maxY < footer.minY - 16 { return }
            app.swipeUp()
        }
        XCTAssertTrue(element.isHittable)
    }

    private func checkNewOnboarding(id: String, greeting: String) {
        let app = XCUIApplication()
        // Pin the launch language to English: the app now ships a Spanish
        // string catalog, and these assertions match English UI copy. The
        // stored per-app language override ("app_ui_language") stays unset.
        app.launchArguments = ["--preview", "--preview-onboarding",
                               "-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
        app.launch()
        let choice = app.buttons["onboarding-language-\(id)"]
        XCTAssertTrue(choice.waitForExistence(timeout: 10))
        reveal(choice, in: app)
        choice.tap()
        XCTAssertTrue(choice.isSelected)
        let screen = XCTAttachment(screenshot: app.screenshot())
        screen.name = "Language selection - \(id)"; screen.lifetime = .keepAlways; add(screen)
        app.buttons["onboarding-continue"].tap()
        XCTAssertTrue(app.buttons["onboarding-meaning-picker"].waitForExistence(timeout: 5))
        app.buttons["onboarding-continue"].tap()
        XCTAssertTrue(app.staticTexts["target-caption"].waitForExistence(timeout: 5))
        XCTAssertEqual(app.staticTexts["target-caption"].label, greeting)
        XCTAssertEqual(app.staticTexts["meaning-caption"].label, "Hi!")
        XCTAssertEqual(app.staticTexts["microphone-status"].label, "Microphone off")
        if id == "zh" {
            XCTAssertEqual(app.staticTexts["pinyin-reading"].label, "nǐhǎo！")
            app.buttons["pinyin-toggle"].tap()
            XCTAssertFalse(app.staticTexts["pinyin-reading"].exists)
            app.buttons["pinyin-toggle"].tap()
            XCTAssertTrue(app.staticTexts["pinyin-reading"].exists)
        }
    }

    func testGermanOnboarding() { checkNewOnboarding(id: "de", greeting: "Hallo!") }
    func testItalianOnboarding() { checkNewOnboarding(id: "it", greeting: "Ciao!") }
    func testBrazilianPortugueseOnboarding() { checkNewOnboarding(id: "pt", greeting: "Olá!") }
    func testMandarinOnboardingWithOptionalPinyin() { checkNewOnboarding(id: "zh", greeting: "你好！") }

    func testMandarinSelectionAtLargestAccessibilityTextSize() {
        let app = XCUIApplication()
        app.launchArguments = ["--preview", "--preview-onboarding", "-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL"]
        app.launch()
        let choice = app.buttons["onboarding-language-zh"]
        XCTAssertTrue(choice.waitForExistence(timeout: 10))
        reveal(choice, in: app)
        choice.tap()
        XCTAssertTrue(choice.isSelected)
        XCTAssertTrue(app.buttons["onboarding-continue"].isHittable)
        app.buttons["onboarding-continue"].tap()
        XCTAssertTrue(app.buttons["onboarding-meaning-picker"].waitForExistence(timeout: 5))
        let privacy = app.descendants(matching: .any).matching(identifier: "onboarding-privacy-policy").firstMatch
        reveal(privacy, in: app)
        XCTAssertTrue(app.staticTexts["onboarding-ai-consent"].exists)
        XCTAssertTrue(app.buttons["onboarding-continue"].isHittable)
        let screen = XCTAttachment(screenshot: app.screenshot())
        screen.name = "Mandarin onboarding - largest accessibility text"; screen.lifetime = .keepAlways; add(screen)
        app.buttons["onboarding-continue"].tap()
        XCTAssertTrue(app.staticTexts["target-caption"].waitForExistence(timeout: 5))
        XCTAssertEqual(app.staticTexts["target-caption"].label, "你好！")
    }

    func testUnsupportedTopicSearchExplainsCapability() {
        let app = XCUIApplication()
        app.launchArguments = ["--preview", "--preview-onboarding", "--preview-topic-search-unsupported"]
        app.launch()

        let language = app.buttons["onboarding-language-en"]
        XCTAssertTrue(language.waitForExistence(timeout: 10))
        reveal(language, in: app)
        language.tap()
        app.buttons["onboarding-continue"].tap()
        XCTAssertTrue(app.buttons["onboarding-meaning-picker"].waitForExistence(timeout: 5))
        app.buttons["onboarding-continue"].tap()
        XCTAssertTrue(app.staticTexts["target-caption"].waitForExistence(timeout: 5))

        app.tabBars.buttons["Themes"].tap()
        let currentTopic = app.buttons["current-topic-entry"]
        XCTAssertTrue(currentTopic.waitForExistence(timeout: 5))
        reveal(currentTopic, in: app)
        currentTopic.tap()

        let warning = app.staticTexts["topic-search-capability-explanation"]
        XCTAssertTrue(warning.waitForExistence(timeout: 5))
        XCTAssertEqual(warning.label, "Source-backed topic search isn’t available with the selected conversation engine. You can still use saved topics.")
    }

    func testNewLanguageSettingsThemesWordsAndReturnToNorwegian() {
        let app = launch()
        for (selection, name, greeting, theme) in [
            ("German · Germany", "German", "Hallo!", "Ein Kaffee?"),
            ("Italian · Italy", "Italian", "Ciao!", "Un caffè?"),
            ("Portuguese · Brazil", "Portuguese", "Olá!", "Um cafezinho?"),
            ("Mandarin Chinese · Mainland China", "Mandarin Chinese", "你好！", "喝杯咖啡？")
        ] {
            app.buttons["Settings"].tap()
            app.buttons["learning-language-picker"].tap()
            app.buttons[selection].tap()
            app.buttons["Done"].tap()
            XCTAssertEqual(app.staticTexts["target-caption"].label, greeting)
            app.tabBars.buttons["Themes"].tap()
            XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label CONTAINS %@", theme)).firstMatch.exists)
            app.tabBars.buttons["Words"].tap()
            XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label ==[c] %@", "Little by little · \(name)")).firstMatch.exists)
            app.tabBars.buttons["Talk"].tap()
        }
        app.buttons["Settings"].tap()
        app.buttons["learning-language-picker"].tap()
        app.buttons["Norwegian · Bokmål"].tap()
        app.buttons["Done"].tap()
        XCTAssertEqual(app.staticTexts["target-caption"].label, "Hei!")
        XCTAssertFalse(app.buttons["pinyin-toggle"].exists)
    }

    func testSimplifiedChineseMeaningsAreAvailableInOnboarding() {
        let app = XCUIApplication()
        app.launchArguments = ["--preview", "--preview-onboarding"]
        app.launch()
        XCTAssertTrue(app.buttons["onboarding-continue"].waitForExistence(timeout: 10))
        app.buttons["onboarding-continue"].tap()
        app.buttons["onboarding-meaning-picker"].tap()
        app.buttons["Chinese (Simplified)"].tap()
        XCTAssertEqual(app.staticTexts["onboarding-meaning-example"].label, "你好！")
        app.buttons["onboarding-continue"].tap()
        XCTAssertTrue(app.staticTexts["meaning-caption"].waitForExistence(timeout: 5))
        XCTAssertEqual(app.staticTexts["meaning-caption"].label, "你好！")
    }

    func testMandarinTranscriptRetainsSourceTextAndPinyinAfterReset() {
        let app = XCUIApplication()
        app.launchArguments = ["--preview", "--ended-conversation", "--preview-language=zh"]
        app.launch()
        XCTAssertTrue(app.buttons["new-conversation"].waitForExistence(timeout: 10))
        XCTAssertEqual(app.staticTexts["target-caption"].label, "我喜欢喝咖啡。")
        XCTAssertEqual(app.staticTexts.matching(identifier: "pinyin-reading").firstMatch.label, "wǒ xǐhuān hē kāfēi。")
        app.buttons["Conversation transcript"].tap()
        XCTAssertTrue(app.staticTexts["我喜欢喝咖啡。"].exists)
        XCTAssertEqual(app.staticTexts.matching(identifier: "pinyin-reading").firstMatch.label, "wǒ xǐhuān hē kāfēi。")
        let screen = XCTAttachment(screenshot: app.screenshot())
        screen.name = "Mandarin transcript and pinyin"; screen.lifetime = .keepAlways; add(screen)
        app.buttons["Done"].tap()
        app.buttons["new-conversation"].tap()
        XCTAssertEqual(app.staticTexts["target-caption"].label, "你好！")
        app.tabBars.buttons["Words"].tap()
        app.buttons["Past conversations"].tap()
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "喝杯咖啡？")).firstMatch.exists)
    }

    private func launch(ended: Bool = false) -> XCUIApplication {
        let app = XCUIApplication(); app.launchArguments = ["--preview"] + (ended ? ["--ended-conversation"] : [])
        app.launch(); return app
    }

    private func launchPronunciationLifecycleFixture() -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["--preview", "--pronunciation-lifecycle-ui-test"]
        app.launch()
        app.buttons["Settings"].tap()
        let preference = app.switches["Pronunciation practice (beta)"]
        XCTAssertTrue(preference.waitForExistence(timeout: 5))
        if preference.value as? String != "1" { preference.tap() }
        app.buttons["Done"].tap()
        app.tabBars.buttons["Learn"].tap()
        app.buttons["language-en"].tap()
        app.buttons["lesson-open-en-foundations-u1-introduce"].tap()
        XCTAssertTrue(app.buttons["pronunciation-check"].waitForExistence(timeout: 5))
        return app
    }

    func testBackgroundCancelsMountedPronunciationAttempt() {
        let app = launchPronunciationLifecycleFixture()
        let cleanupCount = app.staticTexts["pronunciation-cleanup-count"]
        XCTAssertEqual(cleanupCount.label, "Cleanup count: 0")
        app.buttons["pronunciation-check"].tap()
        XCTAssertTrue(app.staticTexts["Checking…"].waitForExistence(timeout: 5))

        XCUIDevice.shared.press(.home)
        app.activate()

        XCTAssertTrue(app.buttons["pronunciation-check"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.staticTexts["Checking…"].exists)
        XCTAssertEqual(cleanupCount.label, "Cleanup count: 1")
    }

    func testNavigationCancelsPronunciationAttemptBeforeLessonCanBeRetried() {
        let app = launchPronunciationLifecycleFixture()
        let cleanupCount = app.staticTexts["pronunciation-cleanup-count"]
        XCTAssertEqual(cleanupCount.label, "Cleanup count: 0")
        app.buttons["pronunciation-check"].tap()
        XCTAssertTrue(app.staticTexts["Checking…"].waitForExistence(timeout: 5))
        app.buttons["Back to lessons"].tap()
        XCTAssertEqual(cleanupCount.waitForExistence(timeout: 5), true)
        XCTAssertEqual(cleanupCount.label, "Cleanup count: 1")

        app.buttons["lesson-open-en-foundations-u1-introduce"].tap()
        XCTAssertTrue(app.buttons["pronunciation-check"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.staticTexts["Checking…"].exists)
    }

    private func launchAccountRecoveryFixture(scenario: String, account: String = "A") -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = [
            "--preview",
            "--exam-recovery-ui-test=\(scenario)",
            "--exam-recovery-account=\(account)",
        ]
        app.launch()
        return app
    }

    private func waitForExamSyncToFinish(_ app: XCUIApplication) {
        let button = app.buttons["weekly-exam-sync"]
        XCTAssertTrue(button.waitForExistence(timeout: 5))
        expectation(for: NSPredicate(format: "isEnabled == true"), evaluatedWith: button)
        waitForExpectations(timeout: 10)
    }

    func testPreviewWithoutRecoveryFixtureHidesAuthenticatedExamActions() {
        let app = launch()
        app.tabBars.buttons["Learn"].tap()
        app.buttons["weekly-exams-entry"].tap()
        XCTAssertFalse(app.buttons["weekly-exam-sync"].exists)
        XCTAssertFalse(app.buttons["weekly-exam-restore"].exists)
    }

    func testKnownRecoveryFixtureExposesAuthenticatedExamActions() {
        let app = launchAccountRecoveryFixture(scenario: "restore")
        app.tabBars.buttons["Learn"].tap()
        app.buttons["weekly-exams-entry"].tap()
        XCTAssertTrue(app.buttons["weekly-exam-sync"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["weekly-exam-restore"].exists)
    }

    func testUnknownRecoveryFixtureFailsClosed() {
        let app = launchAccountRecoveryFixture(scenario: "unknown", account: "C")
        app.tabBars.buttons["Learn"].tap()
        app.buttons["weekly-exams-entry"].tap()
        XCTAssertFalse(app.buttons["weekly-exam-sync"].exists)
        XCTAssertFalse(app.buttons["weekly-exam-restore"].exists)
    }

    func testRecoveryFixtureRestoresPaginatedHistoryAndOriginalResult() {
        let app = launchAccountRecoveryFixture(scenario: "restore")
        app.tabBars.buttons["Learn"].tap()
        app.buttons["weekly-exams-entry"].tap()
        app.buttons["weekly-exam-restore"].tap()

        let first = app.buttons["exam-history-70000000-0000-4000-8000-000000000001"]
        let second = app.buttons["exam-history-70000000-0000-4000-8000-000000000002"]
        let retired = app.buttons["exam-history-70000000-0000-4000-8000-000000000003"]
        XCTAssertTrue(first.waitForExistence(timeout: 10))
        XCTAssertTrue(second.waitForExistence(timeout: 10))
        XCTAssertTrue(retired.waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["A1 · week 202641"].exists)
        // The retired generated week restores from its own frozen snapshot.
        XCTAssertTrue(app.staticTexts["A1 · week 202639"].exists)
        // History sorts by updatedAt DESC: the tapped attempt may sit below the
        // fold now that the page holds three rows.
        app.swipeDown()
        first.tap()
        let result = app.staticTexts["weekly-exam-result"]
        XCTAssertTrue(result.waitForExistence(timeout: 5))
        XCTAssertEqual(result.label, "Score: 100% · Passed")
        // Every restored question keeps its own saved answer from the frozen
        // attempt (the review lists all items with their original answers).
        let savedAnswer = app.staticTexts.matching(
            NSPredicate(format: "label BEGINSWITH %@", "Your answer:")).firstMatch
        XCTAssertTrue(savedAnswer.waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["weekly-exam-attempt-back"].exists)
        app.buttons["weekly-exam-attempt-back"].tap()
        retired.tap()
        XCTAssertTrue(app.staticTexts["weekly-exam-result"].waitForExistence(timeout: 5))
    }

    func testRecoveryFixtureConflictTakeoverAdoptsServerAnswer() {
        let app = launchAccountRecoveryFixture(scenario: "conflict-takeover")
        app.tabBars.buttons["Learn"].tap()
        app.buttons["weekly-exams-entry"].tap()
        app.buttons["weekly-exam-start-en-A1"].tap()
        XCTAssertTrue(app.staticTexts["weekly-exam-current-prompt"].waitForExistence(timeout: 10))
        app.buttons["weekly-exam-attempt-back"].tap()
        app.buttons["weekly-exam-sync"].tap()
        waitForExamSyncToFinish(app)

        let attempt = app.buttons.matching(
            NSPredicate(format: "identifier BEGINSWITH %@", "exam-history-")
        ).firstMatch
        XCTAssertTrue(attempt.waitForExistence(timeout: 10))
        attempt.tap()
        let option = app.buttons.matching(identifier: "weekly-exam-option").firstMatch
        if option.waitForExistence(timeout: 1) { option.tap() }
        else {
            let answer = app.descendants(matching: .any)
                .matching(identifier: "weekly-exam-written-answer").firstMatch
            XCTAssertTrue(answer.waitForExistence(timeout: 5))
            answer.tap()
            answer.typeText("A divergent local answer")
        }
        app.buttons["weekly-exam-save"].tap()
        app.buttons["weekly-exam-attempt-back"].tap()
        app.buttons["weekly-exam-sync"].tap()

        let takeover = app.buttons.matching(
            NSPredicate(format: "identifier BEGINSWITH %@", "exam-conflict-takeover-")
        ).firstMatch
        XCTAssertTrue(takeover.waitForExistence(timeout: 10))
        takeover.tap()
        XCTAssertFalse(app.buttons.matching(
            NSPredicate(format: "identifier BEGINSWITH %@", "exam-conflict-takeover-")
        ).firstMatch.waitForExistence(timeout: 1))

        // Adoption is observable: the reopened attempt shows the account's
        // server-confirmed answer, never the divergent local one.
        let adopted = app.buttons.matching(
            NSPredicate(format: "identifier BEGINSWITH %@", "exam-history-")
        ).firstMatch
        XCTAssertTrue(adopted.waitForExistence(timeout: 5))
        adopted.tap()
        let serverAnswer = app.staticTexts.matching(
            NSPredicate(format: "label == %@", "Your answer: Server-confirmed takeover answer")
        ).firstMatch
        XCTAssertTrue(serverAnswer.waitForExistence(timeout: 10))
        XCTAssertFalse(app.staticTexts.matching(
            NSPredicate(format: "label == %@", "Your answer: A divergent local answer")
        ).firstMatch.exists)
    }

    func testRecoveryFixtureOfflineSyncStaysPendingAndLocal() {
        let app = launchAccountRecoveryFixture(scenario: "sync-unavailable")
        app.tabBars.buttons["Learn"].tap()
        app.buttons["weekly-exams-entry"].tap()
        app.buttons["weekly-exam-start-en-A1"].tap()
        XCTAssertTrue(app.staticTexts["weekly-exam-current-prompt"].waitForExistence(timeout: 10))
        app.buttons["weekly-exam-attempt-back"].tap()

        // The network is dead: the creation event can never leave the device.
        app.buttons["weekly-exam-sync"].tap()
        let message = app.staticTexts["weekly-exam-sync-message"]
        XCTAssertTrue(message.waitForExistence(timeout: 10))

        // The attempt stays visible as pending — saved locally, NOT protected
        // by the cloud — and never leaks to another account.
        let pending = app.staticTexts.matching(
            NSPredicate(format: "label CONTAINS %@", "waiting to sync")).firstMatch
        XCTAssertTrue(pending.waitForExistence(timeout: 5))
        let attempt = app.buttons.matching(
            NSPredicate(format: "identifier BEGINSWITH %@", "exam-history-")
        ).firstMatch
        XCTAssertTrue(attempt.exists)
        app.buttons["exam-recovery-switch-account"].tap()
        XCTAssertFalse(attempt.waitForExistence(timeout: 2))
        app.buttons["exam-recovery-switch-account"].tap()
        XCTAssertTrue(attempt.waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts.matching(
            NSPredicate(format: "label CONTAINS %@", "waiting to sync")).firstMatch.exists)
    }

    func testRecoveryFixtureInFlightAccountSwitchNeverRendersForeignRows() {
        let app = launchAccountRecoveryFixture(scenario: "restore-race")
        app.tabBars.buttons["Learn"].tap()
        app.buttons["weekly-exams-entry"].tap()
        // The first restore page is delayed by the fixture: switch accounts
        // while the response is in flight.
        app.buttons["weekly-exam-restore"].tap()
        app.buttons["exam-recovery-switch-account"].tap()

        // The retained response for owner A must never render under owner B —
        // not during the flight and not after it lands.
        let anyHistory = app.buttons.matching(
            NSPredicate(format: "identifier BEGINSWITH %@", "exam-history-")
        ).firstMatch
        XCTAssertFalse(anyHistory.waitForExistence(timeout: 4))

        // Owner A's data is intact: restoring under the right owner works.
        app.buttons["exam-recovery-switch-account"].tap()
        app.buttons["weekly-exam-restore"].tap()
        XCTAssertTrue(anyHistory.waitForExistence(timeout: 15))
        XCTAssertTrue(app.staticTexts["A1 · week 202641"].exists)
    }

    func testRecoveryFixtureAccountSwitchHidesOtherOwnersHistory() {
        let app = launchAccountRecoveryFixture(scenario: "account-isolation")
        app.tabBars.buttons["Learn"].tap()
        app.buttons["weekly-exams-entry"].tap()
        let start = app.buttons["weekly-exam-start-en-A1"]
        XCTAssertTrue(start.waitForExistence(timeout: 5))
        start.tap()
        XCTAssertTrue(app.staticTexts["weekly-exam-current-prompt"].waitForExistence(timeout: 10))
        app.buttons["weekly-exam-attempt-back"].tap()

        let ownerAHistory = app.buttons.matching(
            NSPredicate(format: "identifier BEGINSWITH %@", "exam-history-")
        ).firstMatch
        XCTAssertTrue(ownerAHistory.waitForExistence(timeout: 5))
        app.buttons["exam-recovery-switch-account"].tap()
        XCTAssertFalse(ownerAHistory.waitForExistence(timeout: 1))
        app.buttons["weekly-exam-sync"].tap()
        XCTAssertFalse(app.staticTexts["weekly-exam-sync-message"].waitForExistence(timeout: 2))
        app.buttons["exam-recovery-switch-account"].tap()
        XCTAssertTrue(ownerAHistory.waitForExistence(timeout: 5))
    }

    func testRecoveryFixtureConflictRequiresExplicitFork() {
        let app = launchAccountRecoveryFixture(scenario: "conflict-fork")
        app.tabBars.buttons["Learn"].tap()
        app.buttons["weekly-exams-entry"].tap()
        app.buttons["weekly-exam-start-en-A1"].tap()
        XCTAssertTrue(app.staticTexts["weekly-exam-current-prompt"].waitForExistence(timeout: 10))
        app.buttons["weekly-exam-attempt-back"].tap()
        app.buttons["weekly-exam-sync"].tap()
        waitForExamSyncToFinish(app)

        let attempt = app.buttons.matching(
            NSPredicate(format: "identifier BEGINSWITH %@", "exam-history-")
        ).firstMatch
        XCTAssertTrue(attempt.waitForExistence(timeout: 10))
        attempt.tap()
        let writtenAnswer = app.descendants(matching: .any)
            .matching(identifier: "weekly-exam-written-answer").firstMatch
        if writtenAnswer.exists {
            writtenAnswer.tap()
            writtenAnswer.typeText("A local answer")
        } else {
            let option = app.buttons.matching(identifier: "weekly-exam-option").firstMatch
            XCTAssertTrue(option.waitForExistence(timeout: 5))
            option.tap()
        }
        app.buttons["weekly-exam-save"].tap()
        app.buttons["weekly-exam-attempt-back"].tap()
        app.buttons["weekly-exam-sync"].tap()

        let conflict = app.buttons.matching(
            NSPredicate(format: "identifier BEGINSWITH %@", "exam-conflict-fork-")
        ).firstMatch
        XCTAssertTrue(conflict.waitForExistence(timeout: 10))
        conflict.tap()
        XCTAssertEqual(app.buttons.matching(
            NSPredicate(format: "identifier BEGINSWITH %@", "exam-history-")
        ).count, 2)
    }

    func testRecoveryFixtureConflictAllowsExplicitAccountTakeover() {
        let app = launchAccountRecoveryFixture(scenario: "conflict-takeover")
        app.tabBars.buttons["Learn"].tap()
        app.buttons["weekly-exams-entry"].tap()
        app.buttons["weekly-exam-start-en-A1"].tap()
        XCTAssertTrue(app.staticTexts["weekly-exam-current-prompt"].waitForExistence(timeout: 10))
        app.buttons["weekly-exam-attempt-back"].tap()
        app.buttons["weekly-exam-sync"].tap()
        waitForExamSyncToFinish(app)

        let attempt = app.buttons.matching(
            NSPredicate(format: "identifier BEGINSWITH %@", "exam-history-")
        ).firstMatch
        XCTAssertTrue(attempt.waitForExistence(timeout: 10))
        attempt.tap()
        let option = app.buttons.matching(identifier: "weekly-exam-option").firstMatch
        if option.waitForExistence(timeout: 1) { option.tap() }
        else {
            let answer = app.descendants(matching: .any)
                .matching(identifier: "weekly-exam-written-answer").firstMatch
            XCTAssertTrue(answer.waitForExistence(timeout: 5))
            answer.tap()
            answer.typeText("A divergent local answer")
        }
        app.buttons["weekly-exam-save"].tap()
        app.buttons["weekly-exam-attempt-back"].tap()
        app.buttons["weekly-exam-sync"].tap()

        let takeover = app.buttons.matching(
            NSPredicate(format: "identifier BEGINSWITH %@", "exam-conflict-takeover-")
        ).firstMatch
        XCTAssertTrue(takeover.waitForExistence(timeout: 10))
        takeover.tap()
        XCTAssertFalse(app.buttons.matching(
            NSPredicate(format: "identifier BEGINSWITH %@", "exam-conflict-takeover-")
        ).firstMatch.waitForExistence(timeout: 1))
    }

    func testLearnExposesWeeklyExamEntry() {
        let app = launch()
        app.tabBars.buttons["Learn"].tap()
        let entry = app.buttons["weekly-exams-entry"]
        XCTAssertTrue(entry.waitForExistence(timeout: 5))
        entry.tap()
        XCTAssertTrue(app.staticTexts["weekly-exam-title"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["No saved exams yet"].exists)
    }
    func testWeeklyExamAttemptCanBeReopenedFromHistory() {
        let app = launch()
        app.tabBars.buttons["Learn"].tap()
        app.buttons["weekly-exams-entry"].tap()

        let start = app.buttons["weekly-exam-start-en-A1"]
        XCTAssertTrue(start.waitForExistence(timeout: 5))
        start.tap()
        let prompt = app.staticTexts["weekly-exam-current-prompt"]
        XCTAssertTrue(prompt.waitForExistence(timeout: 10))
        let originalPrompt = prompt.label

        app.buttons["weekly-exam-attempt-back"].tap()
        let savedAttempt = app.buttons.matching(
            NSPredicate(format: "identifier BEGINSWITH %@", "exam-history-")
        ).firstMatch
        XCTAssertTrue(savedAttempt.waitForExistence(timeout: 5))
        savedAttempt.tap()
        XCTAssertTrue(prompt.waitForExistence(timeout: 5))
        XCTAssertEqual(prompt.label, originalPrompt)
    }

    func testCompletedExamReviewKeepsOriginalResultAfterHistoryReopen() {
        let app = launch()
        app.tabBars.buttons["Learn"].tap()
        app.buttons["weekly-exams-entry"].tap()
        app.buttons["weekly-exam-start-en-A1"].tap()

        for _ in 0..<25 {
            let save = app.buttons["weekly-exam-save"]
            XCTAssertTrue(save.waitForExistence(timeout: 5))
            let writtenAnswer = app.descendants(matching: .any)
                .matching(identifier: "weekly-exam-written-answer").firstMatch
            if writtenAnswer.exists {
                writtenAnswer.tap()
                writtenAnswer.typeText("A saved historical answer")
            } else {
                let option = app.buttons.matching(identifier: "weekly-exam-option").firstMatch
                XCTAssertTrue(option.waitForExistence(timeout: 5))
                option.tap()
            }
            save.tap()
        }

        let result = app.staticTexts["weekly-exam-result"]
        XCTAssertTrue(result.waitForExistence(timeout: 10))
        let originalResult = result.label
        app.buttons["weekly-exam-attempt-back"].tap()
        let savedAttempt = app.buttons.matching(
            NSPredicate(format: "identifier BEGINSWITH %@", "exam-history-")
        ).firstMatch
        XCTAssertTrue(savedAttempt.waitForExistence(timeout: 5))
        savedAttempt.tap()
        XCTAssertTrue(result.waitForExistence(timeout: 5))
        XCTAssertEqual(result.label, originalResult)
    }

    func testGreetingAndMeaningToggle() {
        let app = launch()
        XCTAssertTrue(app.staticTexts["target-caption"].waitForExistence(timeout: 10))
        XCTAssertEqual(app.staticTexts["target-caption"].label, "Hei!")
        XCTAssertEqual(app.staticTexts["microphone-status"].label, "Microphone off")
        app.buttons["Hide meaning subtitles"].tap()
        XCTAssertFalse(app.staticTexts["meaning-caption"].exists)
        app.buttons["Show meaning subtitles"].tap()
        XCTAssertEqual(app.staticTexts["meaning-caption"].label, "Hi!")
    }
    func testThemeSurvivesNavigationToWords() {
        let app = launch()
        app.tabBars.buttons["Themes"].tap()
        app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "A coffee?")).firstMatch.tap()
        XCTAssertTrue(app.staticTexts["A coffee?"].exists)
        app.tabBars.buttons["Words"].tap()
        XCTAssertTrue(app.staticTexts["Your words."].exists)
        app.tabBars.buttons["Talk"].tap()
        XCTAssertTrue(app.staticTexts["A coffee?"].exists)
        XCTAssertEqual(app.staticTexts["microphone-status"].label, "Microphone off")
    }
    func testSettingsOfferSecureKeyEntryAndBackups() {
        let app = launch()
        app.buttons["Settings"].tap()
        if app.buttons["managed-account-settings"].exists {
            app.buttons["managed-account-settings"].tap()
            XCTAssertTrue(app.staticTexts["managed-sign-in-agreement"].waitForExistence(timeout: 5))
            XCTAssertTrue(app.buttons["managed-google-sign-in"].isHittable || app.buttons["managed-apple-sign-in"].isHittable)
            XCTAssertFalse(app.staticTexts["managedAccountMessage"].exists)
            XCTAssertFalse(app.buttons["Buy credits"].exists)
            let accountScreen = XCTAttachment(screenshot: app.screenshot())
            accountScreen.name = "Configured account signup"; accountScreen.lifetime = .keepAlways; add(accountScreen)
            app.navigationBars["Account"].buttons.element(boundBy: 0).tap()
        }
        XCTAssertFalse(app.secureTextFields["api-key"].exists)
        app.buttons["advanced-api-key"].tap()
        if !app.secureTextFields["api-key"].exists { app.swipeUp() }
        XCTAssertTrue(app.secureTextFields["api-key"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["Done"].exists)
        app.buttons["Done"].tap()
        XCTAssertTrue(app.buttons["start-conversation"].exists)
    }

    func testSettingsKeepLicensesInNoticesWithoutTransportDetails() {
        let app = launch()
        app.buttons["Settings"].tap()
        for _ in 0..<6 {
            if app.buttons["Open-source notices"].isHittable { break }
            app.swipeUp()
        }
        XCTAssertTrue(app.buttons["Open-source notices"].isHittable)
        XCTAssertFalse(app.staticTexts["WebRTC distribution by stasel, BSD 3-Clause. WebRTC includes third-party open-source components."].exists)
        XCTAssertFalse(app.links["WebRTC licenses"].exists)
        app.buttons["Open-source notices"].tap()
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "Google WebRTC")).firstMatch.waitForExistence(timeout: 5))
    }

    func testExistingUserCanDeclineThenAcceptAIConsentWithoutRepeatingOnboarding() {
        let app = XCUIApplication()
        app.launchArguments = ["--preview", "--preview-existing-user"]
        app.launch()
        XCTAssertTrue(app.buttons["start-conversation"].waitForExistence(timeout: 10))
        XCTAssertFalse(app.buttons["onboarding-language-fr"].exists)
        app.buttons["start-conversation"].tap()
        XCTAssertTrue(app.staticTexts["ai-consent-title"].waitForExistence(timeout: 5))
        app.buttons["ai-consent-decline"].tap()
        XCTAssertEqual(app.staticTexts["microphone-status"].label, "Microphone off")
        app.buttons["start-conversation"].tap()
        XCTAssertTrue(app.staticTexts["ai-consent-title"].waitForExistence(timeout: 5))
        app.buttons["ai-consent-agree"].tap()
        XCTAssertTrue(app.buttons["Done"].waitForExistence(timeout: 5))
        app.buttons["Done"].tap()
        app.buttons["start-conversation"].tap()
        XCTAssertTrue(app.buttons["Done"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.staticTexts["ai-consent-title"].exists)
        XCTAssertFalse(app.buttons["onboarding-language-fr"].exists)
    }

    func testOnboardingChoosesLearningAndSubtitleLanguagesWithoutAnAccount() {
        let app = XCUIApplication()
        app.launchArguments = ["--preview", "--preview-onboarding"]
        app.launch()
        XCTAssertTrue(app.buttons["onboarding-language-fr"].waitForExistence(timeout: 10))
        let languageScreen = XCTAttachment(screenshot: app.screenshot())
        languageScreen.name = "Onboarding - language"; languageScreen.lifetime = .keepAlways; add(languageScreen)
        app.buttons["onboarding-language-fr"].tap()
        app.buttons["onboarding-continue"].tap()
        XCTAssertTrue(app.buttons["onboarding-meaning-picker"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["onboarding-ai-consent"].exists)
        XCTAssertTrue(app.descendants(matching: .any).matching(identifier: "onboarding-privacy-policy").firstMatch.exists)
        XCTAssertEqual(app.buttons["onboarding-continue"].label, "Agree and continue")
        app.buttons["onboarding-meaning-picker"].tap()
        app.buttons["Spanish"].tap()
        XCTAssertEqual(app.staticTexts["onboarding-meaning-example"].label, "¡Hola!")
        let meaningScreen = XCTAttachment(screenshot: app.screenshot())
        meaningScreen.name = "Onboarding - meanings and consent"; meaningScreen.lifetime = .keepAlways; add(meaningScreen)
        app.buttons["onboarding-continue"].tap()
        XCTAssertTrue(app.staticTexts["target-caption"].waitForExistence(timeout: 5))
        XCTAssertEqual(app.staticTexts["target-caption"].label, "Salut !")
        XCTAssertEqual(app.staticTexts["meaning-caption"].label, "¡Hola!")
        XCTAssertEqual(app.staticTexts["microphone-status"].label, "Microphone off")
        XCTAssertFalse(app.secureTextFields["api-key"].exists)
    }

    func testEnglishOnboardingOffersOtherMeaningsAndPreservesAnExplicitChoice() {
        let app = XCUIApplication()
        app.launchArguments = ["--preview", "--preview-onboarding"]
        app.launch()
        XCTAssertTrue(app.buttons["onboarding-language-en"].waitForExistence(timeout: 10))
        app.buttons["onboarding-language-en"].tap()
        app.buttons["onboarding-continue"].tap()
        XCTAssertTrue(app.buttons["onboarding-meaning-picker"].waitForExistence(timeout: 5))
        XCTAssertNotEqual(app.staticTexts["onboarding-meaning-example"].label, "Hi!")
        app.buttons["onboarding-meaning-picker"].tap()
        app.buttons["Spanish"].tap()
        app.buttons["onboarding-back"].tap()
        app.buttons["onboarding-language-fr"].tap()
        app.buttons["onboarding-continue"].tap()
        XCTAssertEqual(app.staticTexts["onboarding-meaning-example"].label, "¡Hola!")
        app.buttons["onboarding-continue"].tap()
        XCTAssertTrue(app.staticTexts["target-caption"].waitForExistence(timeout: 5))
        XCTAssertEqual(app.staticTexts["meaning-caption"].label, "¡Hola!")
    }

    func testSettingsCanSwitchToEnglishAndFrench() {
        let app = launch()
        for (selection, greeting) in [("English · International", "Hi!"), ("French · France", "Salut !")] {
            app.buttons["Settings"].tap()
            app.buttons["learning-language-picker"].tap()
            app.buttons[selection].tap()
            app.buttons["Done"].tap()
            XCTAssertEqual(app.staticTexts["target-caption"].label, greeting)
        }
    }
    func testLanguageSwitchUpdatesGreetingThemesAndWords() {
        let app = launch()
        app.tabBars.buttons["Themes"].tap()
        app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "A coffee?")).firstMatch.tap()
        app.buttons["Settings"].tap()
        app.buttons["learning-language-picker"].tap()
        app.buttons["Spanish · Spain"].tap()
        app.buttons["Done"].tap()
        XCTAssertEqual(app.staticTexts["target-caption"].label, "¡Hola!")
        XCTAssertTrue(app.staticTexts["A little everyday Spanish"].exists)
        app.tabBars.buttons["Themes"].tap()
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "Un café")).firstMatch.exists)
        app.tabBars.buttons["Words"].tap()
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label ==[c] %@", "Little by little · Spanish")).firstMatch.exists)
        app.tabBars.buttons["Talk"].tap()
        app.buttons["Settings"].tap()
        app.buttons["learning-language-picker"].tap()
        app.buttons["Norwegian · Bokmål"].tap()
        app.buttons["Done"].tap()
        XCTAssertEqual(app.staticTexts["target-caption"].label, "Hei!")
    }

    func testMeaningLabelWorksAfterEndingAndManualResetKeepsHistory() {
        let app = launch(ended: true)
        XCTAssertTrue(app.buttons["new-conversation"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["new-conversation"].isHittable)
        XCTAssertTrue(app.buttons["start-conversation"].isHittable)
        XCTAssertTrue(app.buttons["Conversation transcript"].isHittable)
        XCTAssertEqual(app.staticTexts["meaning-caption"].label, "I like coffee.")
        app.buttons["Hide meaning subtitles"].coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.93)).tap()
        XCTAssertFalse(app.staticTexts["meaning-caption"].exists)
        app.buttons["Show meaning subtitles"].coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.93)).tap()
        XCTAssertEqual(app.staticTexts["meaning-caption"].label, "I like coffee.")
        app.buttons["new-conversation"].tap()
        XCTAssertEqual(app.staticTexts["target-caption"].label, "Hei!")
        XCTAssertEqual(app.staticTexts["microphone-status"].label, "Microphone off")
        XCTAssertFalse(app.staticTexts["A coffee?"].exists)
        app.tabBars.buttons["Words"].tap()
        app.buttons["Past conversations"].tap()
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label CONTAINS %@", "A coffee?")).firstMatch.exists)
    }

    func testEndedConversationAutomaticallyReturnsToGreeting() {
        let app = launch(ended: true)
        XCTAssertTrue(app.buttons["new-conversation"].waitForExistence(timeout: 5))
        XCTAssertEqual(app.staticTexts["target-caption"].label, "Jeg liker kaffe.")
        let ready = NSPredicate(format: "label == %@", "Ready when you are")
        expectation(for: ready, evaluatedWith: app.staticTexts["conversation-status"])
        waitForExpectations(timeout: 18)
        XCTAssertEqual(app.staticTexts["target-caption"].label, "Hei!")
        XCTAssertEqual(app.staticTexts["meaning-caption"].label, "Hi!")
        XCTAssertFalse(app.buttons["new-conversation"].exists)
    }

    func testOpenTranscriptRemainsReadableAfterAutomaticReset() {
        let app = launch(ended: true)
        XCTAssertTrue(app.buttons["new-conversation"].waitForExistence(timeout: 5))
        app.buttons["Conversation transcript"].tap()
        XCTAssertTrue(app.staticTexts["I like coffee."].exists)
        let delay = expectation(description: "Allow the 15-second reset to finish")
        DispatchQueue.main.asyncAfter(deadline: .now() + 16) { delay.fulfill() }
        waitForExpectations(timeout: 18)
        XCTAssertTrue(app.staticTexts["Jeg liker kaffe."].exists)
        XCTAssertTrue(app.staticTexts["I like coffee."].exists)
        app.buttons["Done"].tap()
        XCTAssertEqual(app.staticTexts["target-caption"].label, "Hei!")
    }

    func testTypedReplyEditorOpensFocusedWithRetryControls() {
        let app = XCUIApplication()
        app.launchArguments = ["--preview", "--preview-existing-user", "--screenshot=conversation"]
        app.launch()

        app.buttons["typed-reply-open"].tap()
        let editor = app.textViews["typed-reply-editor"]
        XCTAssertTrue(editor.waitForExistence(timeout: 5))
        XCTAssertTrue(editor.hasFocus)
        XCTAssertTrue(app.buttons["typed-reply-send"].exists)
    }
}
