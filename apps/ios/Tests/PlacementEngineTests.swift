import XCTest
@testable import LearningCore

final class PlacementEngineTests: XCTestCase {
    func testEveryGeneratedBankHasTheValidatedShapeAndDeterministicCoreLadder() throws {
        XCTAssertEqual(Set(PlacementRegistry.all.map(\.languageID)).count, PlacementRegistry.all.count)
        for bank in PlacementRegistry.all {
            XCTAssertFalse(bank.items.isEmpty)
            for band in PlacementEngine.bands {
                XCTAssertGreaterThanOrEqual(bank.items.filter { $0.level == band && $0.mode == "core" }.count, 6)
                XCTAssertGreaterThanOrEqual(bank.items.filter { $0.level == band && $0.mode == "listening" }.count, 3)
                XCTAssertGreaterThanOrEqual(bank.items.filter { $0.level == band && $0.mode == "writing" }.count, 3)
            }
        }
    }

    func testCoreAdvancesOnTwoOfThreeAndProducesTheSameSuggestionShape() throws {
        let bank = try XCTUnwrap(PlacementRegistry.forLanguage("en"))
        var state = PlacementEngine.start(bank: bank, startBand: "A1")
        for _ in 0..<3 {
            let item = try XCTUnwrap(PlacementEngine.nextCoreItem(state: state))
            state = PlacementEngine.applyCore(state: state, item: item, attempt: item.exercise.answers[0])
        }
        XCTAssertEqual("A2", state.band)
        let result = PlacementEngine.canDoResult(bank: bank, band: "invalid")
        XCTAssertEqual("A1", result.band)
        XCTAssertEqual("en", result.languageID)
        XCTAssertEqual(100, PlacementEngine.corePercent(state.verdicts))
        let categories = PlacementEngine.categories(asked: state.asked, verdicts: state.verdicts)
        XCTAssertFalse(categories.isEmpty)
        XCTAssertTrue(categories.allSatisfy { $0.score == 100 })
    }

    func testPlacementPreferencesRoundTripResultPerLanguageAndBoundInstructionChoice() throws {
        let result = PlacementEngine.canDoResult(bank: try XCTUnwrap(PlacementRegistry.forLanguage("en")), band: "B1")
        let preferences = PlacementPreferences(resultsByLanguage: ["en": result], instructionsLanguage: "es")
        let encoded = try JSONEncoder().encode(preferences)
        let restored = try JSONDecoder().decode(PlacementPreferences.self, from: encoded)
        XCTAssertEqual(result, restored.resultsByLanguage["en"])
        XCTAssertEqual("es", restored.instructionsLanguage)
        XCTAssertEqual("subtitles", PlacementPreferences(instructionsLanguage: "unsupported").instructionsLanguage)
    }

    func testPlacementPreferencesStorePersistsResultsPerLanguageAndIgnoresInvalidChoice() throws {
        let suite = "t08-placement-\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        let store = PlacementPreferencesStore(defaults: defaults)
        let english = PlacementEngine.canDoResult(bank: try XCTUnwrap(PlacementRegistry.forLanguage("en")), band: "B1")
        let spanish = PlacementEngine.canDoResult(bank: try XCTUnwrap(PlacementRegistry.forLanguage("es")), band: "A2")

        XCTAssertTrue(store.save(result: english))
        XCTAssertTrue(store.save(result: spanish))
        store.setInstructionsLanguage("es")
        store.setInstructionsLanguage("unsupported")

        let restored = store.load()
        XCTAssertEqual(english, restored.resultsByLanguage["en"])
        XCTAssertEqual(spanish, restored.resultsByLanguage["es"])
        XCTAssertEqual("es", restored.instructionsLanguage)
    }
}
