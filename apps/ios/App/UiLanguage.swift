import SwiftUI

/// UI language chosen in Settings. `system` follows the device language
/// (today's behaviour); a concrete language overrides it through
/// `.environment(\.locale, …)` injected at the app root. The choice persists
/// in UserDefaults via @AppStorage, independent of the learning database.
enum UiLanguage: String, CaseIterable, Identifiable {
    case system
    case english = "en"
    case spanish = "es"
    case french = "fr"
    case german = "de"
    case italian = "it"
    case portuguese = "pt"
    case norwegian = "nb"
    case chinese = "zh"

    var id: String { rawValue }

    /// Locale to inject at the root, or nil to follow the device language.
    var locale: Locale? {
        switch self {
        case .system: nil
        case .english: Locale(identifier: "en")
        case .spanish: Locale(identifier: "es")
        case .french: Locale(identifier: "fr")
        case .german: Locale(identifier: "de")
        case .italian: Locale(identifier: "it")
        case .portuguese: Locale(identifier: "pt")
        case .norwegian: Locale(identifier: "nb")
        case .chinese: Locale(identifier: "zh")
        }
    }

    /// Native-language display name (language names stay in their own language).
    var label: LocalizedStringKey {
        switch self {
        case .system: "System"
        case .english: "English"
        case .spanish: "Español"
        case .french: "Français"
        case .german: "Deutsch"
        case .italian: "Italiano"
        case .portuguese: "Português"
        case .norwegian: "Norsk"
        case .chinese: "中文"
        }
    }
}

/// Root-level injection point: reads the stored choice once per render.
struct UiLanguageModifier: ViewModifier {
    @AppStorage("app_ui_language") private var stored: String = UiLanguage.system.rawValue

    func body(content: Content) -> some View {
        if let language = UiLanguage(rawValue: stored), let locale = language.locale {
            content.environment(\.locale, locale)
        } else {
            content
        }
    }
}

extension View {
    /// Applies the in-app UI language choice to the whole hierarchy.
    func appUiLanguage() -> some View { modifier(UiLanguageModifier()) }
}
