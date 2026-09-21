package com.jkdc.looro.core

data class WordState(
    val id:String,val lemma:String,val meaning:String,val form:String,val example:String,val bars:Int,
    val understandingCount:Int,val independentCount:Int,val lastSeen:Double,val dueAt:Double
) {
    val label get() = listOf("New","Fragile","Growing","Steady")[bars.coerceIn(0,3)]
    val explanation get() = when { independentCount==0 -> "Heard or used with support. Try using it in your own words."
        bars==1 -> "Used independently. We’ll bring it back soon."
        bars==2 -> "Recalled on different days. Still worth revisiting."
        else -> "Recalled across days and contexts. Strength can fade with time." }
}
data class LearnerState(val challenge:Int,val observationCount:Int,val nextGoal:String,val capabilities:List<String>,val words:List<WordState>) {
    val levelLabel get() = if (observationCount < 4) "Getting to know you" else "Finding your pace"
}

/** Practice summary shown in the Progress screen; a pure projection, never stored. */
data class LearningStats(
    val totalSessions:Int, val totalVoiceMinutes:Int, val wordsByBars:List<Int>,
    val activeStreakDays:Int, val longestStreakDays:Int, val last14DaysMinutes:List<Double>,
    val capabilityCount:Int, val latestCapability:String?,
)

object LearningEngine {
    fun validate(proposal:Assessment, session:SessionRecord):Assessment? {
        if (LanguageRegistry.get(session.languageID)==null) return null
        val passage=session.passages.firstOrNull { it.id==proposal.passageID && it.speaker==Speaker.user } ?: return null
        if (passage.revisionKey != proposal.revisionKey || proposal.suggestedLevel !in 0..5 || proposal.words.size>12) return null
        val allowed=passage.fragments.map { it.id }.toSet()
        val words=proposal.words.mapNotNull { word ->
            if (word.language != session.languageID || word.sourceIDs.isEmpty() || !allowed.containsAll(word.sourceIDs) ||
                !word.confidence.isFinite() || word.confidence !in 0.8..1.0 || word.lemma.isEmpty() || word.lemma.length>=100 ||
                word.meaning.isEmpty() || word.meaning.length>=180 || word.form.isEmpty() || word.quote.isEmpty() ||
                !passage.text.containsCanonical(word.quote) || !word.quote.containsCanonical(word.form)) return@mapNotNull null
            val refs=passage.fragments.filter { word.sourceIDs.contains(it.id) }.joinToString("") { it.text }
            if (!refs.containsCanonical(word.quote)) return@mapNotNull null
            var out=word
            if (out.kind==EvidenceKind.independent) {
                val modeled=session.passages.any { p -> p.speaker==Speaker.assistant && p.startMS<=passage.startMS && passage.startMS-p.endMS<90000 && p.text.containsCanonical(word.form) }
                if (passage.fragments.any { it.meaningVisible || it.typed } || modeled) out=out.copy(kind=EvidenceKind.assisted)
            }
            out
        }
        return proposal.copy(nextGoal=proposal.nextGoal.take(300),capability=proposal.capability.take(160),words=words)
    }
    fun project(sessions:List<SessionRecord>,languageID:String=LanguageRegistry.defaultID,hiddenWords:List<String> = emptyList(),now:Double=nowSeconds()):LearnerState {
        val hidden=hiddenWords.map { it.canonical() }
        var level=0; var count=0; var successes=0
        var nextGoal="Start with a greeting and one small question. Adjust from what the learner actually says."
        val caps=mutableMapOf<String,MutableSet<String>>()
        val events=mutableMapOf<String,MutableList<Triple<WordProposal,Double,String>>>()
        for (session in sessions.filter { it.languageID==languageID }.sortedBy { it.startedAt }) {
            val seen=mutableSetOf<String>()
            for (raw in session.assessments.sortedBy { it.createdAt }) {
                if (!seen.add(raw.passageID)) continue
                val a=validate(raw,session) ?: continue
                count++
                when(a.outcome) {
                    Outcome.breakdown -> { level=(level-1).coerceAtLeast(0); successes=0 }
                    Outcome.success -> { successes++; if(successes>=2) { level=minOf(5, maxOf(level, minOf(level+1, a.suggestedLevel))); successes=0 } }
                    else -> successes=0
                }
                if(a.nextGoal.isNotEmpty()) nextGoal=a.nextGoal
                if(a.outcome==Outcome.success && a.capability.isNotEmpty()) caps.getOrPut(a.capability){mutableSetOf()}.add("${dayKey(a.createdAt)}|${a.context}")
                val seenWords=mutableSetOf<String>()
                for(word in a.words) if(hidden.none { it==word.key } && seenWords.add(word.key)) events.getOrPut(word.key){mutableListOf()}.add(Triple(word,a.createdAt,a.context))
            }
        }
        val words=events.mapNotNull { (key,obs) ->
            val last=obs.lastOrNull() ?: return@mapNotNull null
            val independent=obs.filter { it.first.kind==EvidenceKind.independent }
            val days=independent.map { dayKey(it.second) }.toSet().size
            val contexts=independent.map { it.third }.toSet().size
            val lastRecall=independent.lastOrNull()?.second
            var bars=if(independent.isEmpty()) 0 else 1
            if(days>=2) bars=2
            if(days>=3 && contexts>=2 && independent.last().second-independent.first().second>=7*86400) bars=3
            val due=(lastRecall ?: last.second)+listOf(1.0,1.0,4.0,14.0)[bars]*86400
            if(now>due && bars>1) bars--
            val lapse=obs.lastOrNull { it.first.kind==EvidenceKind.lapse }
            if(lapse!=null && lapse.second>(lastRecall ?: Double.NEGATIVE_INFINITY)) bars=minOf(bars,1)
            WordState(key,last.first.lemma,last.first.meaning,last.first.form,last.first.quote,bars,
                obs.count { it.first.kind==EvidenceKind.understanding },independent.size,last.second,due)
        }.sortedByDescending { it.lastSeen }
        return LearnerState(level,count,nextGoal,caps.filterValues { it.size>=3 }.keys.sorted(),words)
    }
    private fun dayKey(seconds:Double):String = java.time.Instant.ofEpochSecond((seconds + APPLE_EPOCH_UNIX_SECONDS).toLong()).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()

    /** Read-only practice summary projected from the archive; never stored. */
    fun stats(sessions:List<SessionRecord>,learner:LearnerState,zone:java.time.ZoneId = java.time.ZoneId.systemDefault(),now:Double=nowSeconds()):LearningStats {
        fun epochDay(seconds:Double):Long = java.time.Instant.ofEpochSecond((seconds + APPLE_EPOCH_UNIX_SECONDS).toLong()).atZone(zone).toLocalDate().toEpochDay()
        val minutesByDay=HashMap<Long,Double>()
        sessions.forEach { minutesByDay.merge(epochDay(it.startedAt),it.voiceSeconds/60.0,Double::plus) }
        val today=epochDay(now)
        var active=0
        var cursor=if(minutesByDay.containsKey(today)) today else today-1
        while(minutesByDay.containsKey(cursor)) { active++; cursor-- }
        var longest=0; var run=0; var previous=Long.MIN_VALUE
        for (day in minutesByDay.keys.sorted()) { run=if(day==previous+1) run+1 else 1; if(run>longest) longest=run; previous=day }
        val bars=IntArray(4); learner.words.forEach { bars[it.bars.coerceIn(0,3)]++ }
        return LearningStats(
            totalSessions=sessions.size,
            totalVoiceMinutes=(sessions.sumOf { it.voiceSeconds }/60.0).toInt(),
            wordsByBars=bars.toList(),
            activeStreakDays=active, longestStreakDays=longest,
            last14DaysMinutes=(13 downTo 0).map { offset -> minutesByDay[today-offset] ?: 0.0 },
            capabilityCount=learner.capabilities.size, latestCapability=learner.capabilities.lastOrNull(),
        )
    }
}
