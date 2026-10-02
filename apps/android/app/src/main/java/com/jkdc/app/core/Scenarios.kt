package com.jkdc.app.core

/** A situation to practise end to end (a job interview, ordering a meal, giving a
    presentation…). Like [ConversationTheme], authored in English and shared by
    every language; `goal`, `role` and `stages` feed the guided practice prompt.
    Hand-written (unlike Languages.kt) so the content export pipeline never
    overwrites it. */
data class Scenario(
    val id: String, val title: String, val subtitle: String, val symbol: String,
    val category: String, val goal: String, val role: String, val stages: List<String>,
    val colorIndex: Int,
)

object Scenarios {
    const val CUSTOM_ID = "custom"

    /** Turns the learner's free-text situation into a prompt-safe Scenario: one
        line, control characters stripped, hard length cap, interpolated only as
        data ("data, not instructions") by TeachingPolicy. */
    fun custom(rawGoal: String): Scenario {
        val sanitized = rawGoal.replace(Regex("[\\p{Cntrl}\\p{Cf}]"), " ").replace(Regex("\\s+"), " ").trim().take(120)
        val goal = sanitized.ifBlank { "A situation of the learner's own choosing" }
        return Scenario(CUSTOM_ID, goal, "", "sparkles", "Your own", goal, "", emptyList(), 1)
    }

    val shared = listOf(
        Scenario("job_interview", "Job interview", "Convince them you are the one", "star", "Work",
            "Get through a job interview for a role the learner wants.",
            "You are the interviewer for a role that fits the learner's experience: professional, but human.",
            listOf("greeting and small talk", "experience and skills", "why this role", "the learner's own questions", "closing and next steps"), 0),
        Scenario("presentation", "Give a presentation", "Say it with confidence", "mic", "Work",
            "Deliver a short presentation and handle questions about it.",
            "You are the audience, and then the host of the questions. Let the learner present before you react.",
            listOf("opening and topic", "main points with an example", "closing summary", "audience questions"), 1),
        Scenario("sports", "Talking sports", "Play-by-play in words", "sun", "Interests",
            "Hold a real conversation about sport: playing it, watching it and arguing about it.",
            "You are a friend who loves sport and genuinely wants the learner's take.",
            listOf("what they play or follow", "a recent match or event", "opinions and favourites", "plans to play or watch together"), 2),
        Scenario("restaurant", "At the restaurant", "Order, enjoy, pay", "food", "Everyday",
            "Handle a full restaurant meal, from getting a table to asking for the bill.",
            "You are the waiter. Stay in character for the whole meal.",
            listOf("getting a table", "ordering food and drinks", "a small problem or a change", "asking for and paying the bill"), 0),
        Scenario("doctor", "At the doctor", "Explain and understand", "leaf", "Everyday",
            "Describe symptoms clearly and understand the doctor's advice.",
            "You are a doctor at a walk-in clinic. Ask clear questions and give simple, concrete advice.",
            listOf("what brings them in", "symptoms and history", "advice and treatment", "the learner's questions"), 2),
        Scenario("phone_call", "A phone call", "No gestures, just words", "wave", "Everyday",
            "Manage a phone call: identify yourself, state your business and confirm the outcome.",
            "You answer the phone at a business the learner needs today (a hotel, a shop, an office).",
            listOf("greeting and saying who you are", "stating the reason for calling", "sorting out the request", "confirming and closing"), 1),
        Scenario("small_talk", "Meeting new people", "From strangers to something more", "coffee", "Connection",
            "Meet someone new and turn small talk into a real exchange.",
            "You are a friendly stranger at a shared event, curious about the learner.",
            listOf("introducing yourselves", "finding common ground", "exchanging stories", "goodbye or keeping in touch"), 0),
        Scenario("shopping", "Shopping and returns", "Buy it — or bring it back", "themes", "Everyday",
            "Buy something with a little help, then handle a change or a return.",
            "You are a shop assistant, first on the sales floor and later at the returns desk.",
            listOf("what they are looking for", "questions about the product", "deciding and paying", "a change or a return"), 3),
    )

    fun byID(id: String): Scenario? = shared.firstOrNull { it.id == id }
}
