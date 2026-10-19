import Foundation

/// Placement content is generated from the canonical shared JSON banks.
public struct PlacementItem: Codable, Equatable, Sendable {
    public var id: String
    public var level: String
    public var mode: String
    public var exercise: Exercise
    public var skill: String
    public var speech: String?
    public var isCore: Bool { mode == "core" }
}

public struct PlacementBank: Codable, Equatable, Sendable {
    public var languageID: String
    public var version: Int
    public var items: [PlacementItem]
}

public struct PlacementCategoryScore: Codable, Equatable, Sendable {
    public var name: String
    public var score: Int
    public init(name: String, score: Int) { self.name = name; self.score = score }
}

public struct PlacementResult: Codable, Equatable, Sendable {
    public var languageID: String
    public var version: Int
    public var band: String
    public var categories: [PlacementCategoryScore]
    public var listeningBand: String?
    public var writingBand: String?
    public var confidence: String
    public var corePercent: Int
    public var source: String
    public var at: Int64
    public init(languageID: String, version: Int, band: String, categories: [PlacementCategoryScore] = [],
                listeningBand: String? = nil, writingBand: String? = nil, confidence: String,
                corePercent: Int, source: String, at: Int64 = 0) {
        self.languageID = languageID; self.version = version; self.band = band
        self.categories = categories; self.listeningBand = listeningBand; self.writingBand = writingBand
        self.confidence = confidence; self.corePercent = corePercent; self.source = source; self.at = at
    }
}

/// Local placement UI settings, stored separately from the shared learning archive.
/// Android keeps the same values in its placement-result and instruction stores.
public struct PlacementPreferences: Codable, Equatable, Sendable {
    public var resultsByLanguage: [String: PlacementResult]
    public var instructionsLanguage: String
    public init(resultsByLanguage: [String: PlacementResult] = [:], instructionsLanguage: String = "subtitles") {
        self.resultsByLanguage = resultsByLanguage
        self.instructionsLanguage = ["subtitles", "en", "es"].contains(instructionsLanguage) ? instructionsLanguage : "subtitles"
    }
}

/// Local cache for placement suggestions and the instruction-language choice.
/// Like Android's SharedPreferences stores, it is separate from the cloud archive.
public final class PlacementPreferencesStore {
    private static let key = "placement_preferences"
    private let defaults: UserDefaults

    public init(defaults: UserDefaults = .standard) { self.defaults = defaults }

    public func load() -> PlacementPreferences {
        guard let data = defaults.data(forKey: Self.key),
              let saved = try? JSONDecoder().decode(PlacementPreferences.self, from: data) else {
            return PlacementPreferences()
        }
        return PlacementPreferences(resultsByLanguage: saved.resultsByLanguage,
                                    instructionsLanguage: saved.instructionsLanguage)
    }

    @discardableResult
    public func save(_ preferences: PlacementPreferences) -> Bool {
        let normalized = PlacementPreferences(resultsByLanguage: preferences.resultsByLanguage,
                                              instructionsLanguage: preferences.instructionsLanguage)
        guard let data = try? JSONEncoder().encode(normalized) else { return false }
        defaults.set(data, forKey: Self.key)
        return defaults.data(forKey: Self.key) == data
    }

    @discardableResult
    public func save(result: PlacementResult) -> Bool {
        var preferences = load()
        preferences.resultsByLanguage[result.languageID] = result
        return save(preferences)
    }

    public func setInstructionsLanguage(_ language: String) {
        guard ["subtitles", "en", "es"].contains(language) else { return }
        var preferences = load()
        preferences.instructionsLanguage = language
        _ = save(preferences)
    }
}

public enum PlacementRegistry {
    public static var all: [PlacementBank] { PlacementData.all }
    public static func forLanguage(_ languageID: String) -> PlacementBank? {
        all.first { $0.languageID == languageID }
    }
}

public enum PlacementEngine {
    public static let floor = "A1"
    public static let cap = "C1"
    public static let coreCap = 18
    public static let blockSize = 3
    public static let bands = ["A1", "A2", "B1", "B2", "C1"]

    public struct Core: Sendable {
        public let bank: PlacementBank
        public let band: String
        public let asked: [PlacementItem]
        public let verdicts: [Bool]
        public let window: [Bool]
        public let finished: Bool
        public var answered: Int { asked.count }
    }

    public static func start(bank: PlacementBank, startBand: String) -> Core {
        Core(bank: bank, band: coerce(startBand), asked: [], verdicts: [], window: [], finished: false)
    }

    public static func nextCoreItem(state: Core) -> PlacementItem? {
        guard !state.finished else { return nil }
        let used = Set(state.asked.map(\.id))
        var categoryCounts: [String: Int] = [:]
        for item in state.asked { categoryCounts[categoryOf(item.skill), default: 0] += 1 }
        return state.bank.items.enumerated()
            .filter { $0.element.isCore && $0.element.level == state.band && !used.contains($0.element.id) }
            .min { lhs, rhs in
                let left = categoryCounts[categoryOf(lhs.element.skill), default: 0]
                let right = categoryCounts[categoryOf(rhs.element.skill), default: 0]
                return left == right ? lhs.offset < rhs.offset : left < right
            }?.element
    }

    public static func applyCore(state: Core, item: PlacementItem, attempt: String) -> Core {
        guard !state.finished, item.isCore, item.level == state.band,
              !state.asked.contains(where: { $0.id == item.id }) else { return state }
        let correct = ExerciseEngine.isCorrect(exercise: item.exercise, attempt: attempt)
        let asked = state.asked + [item]
        let verdicts = state.verdicts + [correct]
        var window = (state.window + [correct]).suffix(3)
        var band = state.band
        var finished = asked.count >= coreCap
        if window.count == 3 {
            if window.filter({ $0 }).count >= 2 {
                if band == cap { finished = true }
                else { band = adjacentBand(band, by: 1); window = [] }
            } else {
                if band == floor { finished = true }
                else { band = adjacentBand(band, by: -1); window = [] }
            }
        }
        return Core(bank: state.bank, band: band, asked: asked, verdicts: verdicts,
                    window: Array(window), finished: finished)
    }

    public static func blockItems(bank: PlacementBank, band: String, mode: String) -> [PlacementItem] {
        Array(bank.items.filter { $0.mode == mode && $0.level == band }.prefix(blockSize))
    }

    public static func blockBand(globalBand: String, verdicts: [Bool]) -> String {
        verdicts.count >= 2 && verdicts.filter({ $0 }).count >= 2 ? globalBand : adjacentBand(globalBand, by: -1)
    }

    public static func confidence(coreAnswered: Int) -> String {
        coreAnswered < 15 ? "high" : (coreAnswered < coreCap ? "medium" : "low")
    }

    public static func categories(asked: [PlacementItem], verdicts: [Bool]) -> [PlacementCategoryScore] {
        var values: [String: (correct: Int, total: Int)] = [:]
        for (index, item) in asked.enumerated() {
            let key = categoryOf(item.skill)
            var score = values[key, default: (0, 0)]
            score.total += 1
            if verdicts.indices.contains(index) && verdicts[index] { score.correct += 1 }
            values[key] = score
        }
        return values.keys.sorted().map { key in
            let value = values[key]!
            return PlacementCategoryScore(name: key, score: value.total == 0 ? 0 : value.correct * 100 / value.total)
        }
    }

    public static func corePercent(_ verdicts: [Bool]) -> Int { percent(verdicts) }

    public static func canDoResult(bank: PlacementBank, band: String) -> PlacementResult {
        PlacementResult(languageID: bank.languageID, version: bank.version, band: coerce(band),
                        confidence: "low", corePercent: 0, source: "cando")
    }

    private static func categoryOf(_ skill: String) -> String {
        SkillsData.categories[skill] ?? String(skill.split(separator: ":", maxSplits: 1).first ?? "")
    }
    private static func coerce(_ band: String) -> String { bands.contains(band) ? band : floor }
    private static func adjacentBand(_ band: String, by offset: Int) -> String {
        let index = bands.firstIndex(of: band) ?? 0
        return bands[min(max(index + offset, 0), bands.count - 1)]
    }
    private static func percent(_ verdicts: [Bool]) -> Int {
        verdicts.isEmpty ? 0 : verdicts.filter({ $0 }).count * 100 / verdicts.count
    }
}
