import XCTest
@testable import App

/// Gates the string catalog: every user-facing key must carry a non-empty
/// translation for every supported UI language (the LanguageRegistry learning
/// languages) and no placeholder (new/review) state, so a missing translation
/// fails here instead of surfacing as English text for that language's users.
final class LocalizationCatalogTests: XCTestCase {
    /// Must match the language sets in Android `AppLanguage` and
    /// `scripts/check_ui_strings.py`.
    static let supportedLanguages = ["en", "es", "fr", "de", "it", "pt", "nb", "zh"]

    private func loadCatalog() throws -> [String: Any] {
        let bundle = Bundle(for: AppRoot.self)
        let url = try XCTUnwrap(bundle.url(forResource: "Localizable", withExtension: "xcstrings"),
                                "Localizable.xcstrings must ship in the App bundle")
        return try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any],
                             "Localizable.xcstrings must be valid JSON")
    }

    func testCatalogParsesWithExpectedShape() throws {
        let catalog = try loadCatalog()
        XCTAssertEqual(catalog["sourceLanguage"] as? String, "en")
        let strings = try XCTUnwrap(catalog["strings"] as? [String: Any], "catalog must contain a strings table")
        XCTAssertFalse(strings.isEmpty, "catalog must not be empty")
    }

    func testEveryKeyHasNonEmptyTranslationInEverySupportedLanguage() throws {
        let catalog = try loadCatalog()
        let strings = try XCTUnwrap(catalog["strings"] as? [String: Any])
        for language in Self.supportedLanguages {
            var missing: [String] = []
            var empty: [String] = []
            for (key, value) in strings {
                let entry = try XCTUnwrap(value as? [String: Any], "entry \(key) must be an object")
                let localizations = entry["localizations"] as? [String: Any] ?? [:]
                guard let unit = (localizations[language] as? [String: Any])?["stringUnit"] as? [String: Any],
                      let translation = unit["value"] as? String else {
                    missing.append(key); continue
                }
                if translation.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { empty.append(key) }
            }
            XCTAssertTrue(missing.isEmpty, "keys missing \(language): \(missing)")
            XCTAssertTrue(empty.isEmpty, "keys with empty \(language): \(empty)")
        }
    }

    func testNoKeyIsInPlaceholderState() throws {
        let catalog = try loadCatalog()
        let strings = try XCTUnwrap(catalog["strings"] as? [String: Any])
        var placeholders: [String] = []
        for (key, value) in strings {
            let entry = try XCTUnwrap(value as? [String: Any])
            let localizations = entry["localizations"] as? [String: Any] ?? [:]
            for (_, localization) in localizations {
                let unit = localization as? [String: Any]
                if (unit?["stringUnit"] as? [String: Any])?["state"] as? String != "translated" {
                    placeholders.append(key)
                }
            }
        }
        XCTAssertTrue(placeholders.isEmpty, "keys not fully translated: \(placeholders)")
    }
}
