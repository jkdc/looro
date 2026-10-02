import Foundation

/// A situation to practise end to end (a job interview, ordering a meal, giving a
/// presentation…). Like `ConversationTheme`, authored in English and shared by
/// every language; `goal`, `role` and `stages` feed the guided practice prompt.
/// Mirror of `apps/android/.../core/Scenarios.kt`; the scenario practice session
/// itself is Android-first (see openspec/changes/scenario-practice).
public struct Scenario: Sendable {
    public let id: String, title: String, subtitle: String, symbol: String
    public let category: String, goal: String, role: String, stages: [String], colorIndex: Int

    public init(id: String, title: String, subtitle: String, symbol: String,
                category: String, goal: String, role: String, stages: [String], colorIndex: Int) {
        self.id = id; self.title = title; self.subtitle = subtitle; self.symbol = symbol
        self.category = category; self.goal = goal; self.role = role; self.stages = stages; self.colorIndex = colorIndex
    }
}

public enum Scenarios {
    public static let CUSTOM_ID = "custom"

    /// Turns the learner's free-text situation into a prompt-safe Scenario: one
    /// line, control characters stripped, hard length cap, interpolated only as
    /// data ("data, not instructions") by TeachingPolicy.
    public static func custom(_ rawGoal: String) -> Scenario {
        let stripped = String(String.UnicodeScalarView(rawGoal.unicodeScalars.filter { scalar in
            let category = scalar.properties.generalCategory
            return category != .control && category != .format
        }))
        let collapsed = stripped.replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression)
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let goal = String(collapsed.prefix(120))
        let safe = goal.isEmpty ? "A situation of the learner's own choosing" : goal
        return Scenario(id: CUSTOM_ID, title: safe, subtitle: "", symbol: "sparkles", category: "Your own",
                        goal: safe, role: "", stages: [], colorIndex: 1)
    }

    public static let shared: [Scenario] = [
        Scenario(id: "job_interview", title: "Job interview", subtitle: "Convince them you are the one", symbol: "star", category: "Work",
                 goal: "Get through a job interview for a role the learner wants.",
                 role: "You are the interviewer for a role that fits the learner's experience: professional, but human.",
                 stages: ["greeting and small talk", "experience and skills", "why this role", "the learner's own questions", "closing and next steps"], colorIndex: 0),
        Scenario(id: "presentation", title: "Give a presentation", subtitle: "Say it with confidence", symbol: "mic", category: "Work",
                 goal: "Deliver a short presentation and handle questions about it.",
                 role: "You are the audience, and then the host of the questions. Let the learner present before you react.",
                 stages: ["opening and topic", "main points with an example", "closing summary", "audience questions"], colorIndex: 1),
        Scenario(id: "sports", title: "Talking sports", subtitle: "Play-by-play in words", symbol: "sun", category: "Interests",
                 goal: "Hold a real conversation about sport: playing it, watching it and arguing about it.",
                 role: "You are a friend who loves sport and genuinely wants the learner's take.",
                 stages: ["what they play or follow", "a recent match or event", "opinions and favourites", "plans to play or watch together"], colorIndex: 2),
        Scenario(id: "restaurant", title: "At the restaurant", subtitle: "Order, enjoy, pay", symbol: "food", category: "Everyday",
                 goal: "Handle a full restaurant meal, from getting a table to asking for the bill.",
                 role: "You are the waiter. Stay in character for the whole meal.",
                 stages: ["getting a table", "ordering food and drinks", "a small problem or a change", "asking for and paying the bill"], colorIndex: 0),
        Scenario(id: "doctor", title: "At the doctor", subtitle: "Explain and understand", symbol: "leaf", category: "Everyday",
                 goal: "Describe symptoms clearly and understand the doctor's advice.",
                 role: "You are a doctor at a walk-in clinic. Ask clear questions and give simple, concrete advice.",
                 stages: ["what brings them in", "symptoms and history", "advice and treatment", "the learner's questions"], colorIndex: 2),
        Scenario(id: "phone_call", title: "A phone call", subtitle: "No gestures, just words", symbol: "wave", category: "Everyday",
                 goal: "Manage a phone call: identify yourself, state your business and confirm the outcome.",
                 role: "You answer the phone at a business the learner needs today (a hotel, a shop, an office).",
                 stages: ["greeting and saying who you are", "stating the reason for calling", "sorting out the request", "confirming and closing"], colorIndex: 1),
        Scenario(id: "small_talk", title: "Meeting new people", subtitle: "From strangers to something more", symbol: "coffee", category: "Connection",
                 goal: "Meet someone new and turn small talk into a real exchange.",
                 role: "You are a friendly stranger at a shared event, curious about the learner.",
                 stages: ["introducing yourselves", "finding common ground", "exchanging stories", "goodbye or keeping in touch"], colorIndex: 0),
        Scenario(id: "shopping", title: "Shopping and returns", subtitle: "Buy it — or bring it back", symbol: "themes", category: "Everyday",
                 goal: "Buy something with a little help, then handle a change or a return.",
                 role: "You are a shop assistant, first on the sales floor and later at the returns desk.",
                 stages: ["what they are looking for", "questions about the product", "deciding and paying", "a change or a return"], colorIndex: 3),
    ]

    public static func byID(_ id: String) -> Scenario? {
        shared.first { $0.id == id }
    }
}
