package com.jkdc.looro.core

/** Retained in memory across Activity recreation; never written into backups or saved state. */
sealed interface CloudAction {
    data object StartVoice : CloudAction
    data class SendTyped(val text: String) : CloudAction
    data class Lookup(val word: String, val sentence: String) : CloudAction
    data class CurrentTopic(val query: String) : CloudAction
    data class StartLesson(val lessonID: String) : CloudAction
    data object Help : CloudAction
}
