#!/usr/bin/env python3
"""One-shot authoring helper: injects the per-lesson `exercises` blocks into
shared/courses/en-foundations.json as a targeted textual insertion, so the
hand-written formatting of the rest of the file stays untouched. Run once;
the canonical file is hand-edited afterwards. (Kept for provenance; not run in CI.)"""
import json
import pathlib

PATH = pathlib.Path(__file__).resolve().parents[1] / 'shared' / 'courses' / 'en-foundations.json'

E = {}
def add(lesson, exercises):
    E[lesson] = [dict(id=f"{lesson}-e{i + 1}", **exercise) for i, exercise in enumerate(exercises)]

add('en-foundations-u1-to-be', [
    dict(kind='choice', prompt='She ___ a doctor.', options=['is', 'are', 'am', 'be'], answers=['is'],
         explain='With he, she and it, to be becomes is.'),
    dict(kind='gap', prompt='They ___ my friends.', answers=['are'],
         explain='With they, to be is always are.'),
    dict(kind='order', prompt='Say where you are from.', answers=['I am from Spain'],
         explain='Am goes with I: I am from …'),
    dict(kind='written', prompt='Introduce yourself in one sentence: say your name.',
         answers=["I'm <name>", 'My name is <name>', 'I am <name>'],
         explain="I'm Ana / My name is Ana — the verb is never dropped."),
])
add('en-foundations-u1-questions', [
    dict(kind='choice', prompt='___ you tired?', options=['Are', 'Is', 'Am', 'Be'], answers=['Are'],
         explain='With you, the question starts with Are.'),
    dict(kind='gap', prompt='___ she your sister?', answers=['is'],
         explain='With she, questions start with Is.'),
    dict(kind='order', prompt='Ask where the person is from.', answers=['Where are you from'],
         explain='Question word + are + you.'),
    dict(kind='written', prompt='Make it negative: "I am late."',
         answers=["I'm not late", 'I am not late'],
         explain="I'm not is already short; is not → isn't, are not → aren't."),
])
add('en-foundations-u1-introduce', [
    dict(kind='choice', prompt='I ___ from Spain.', options=['am', 'is', 'are', 'be'], answers=['am'],
         explain='With I, always am — even in short answers.'),
    dict(kind='gap', prompt='Nice to ___ you.', answers=['meet'],
         explain='The fixed greeting is Nice to meet you.'),
    dict(kind='order', prompt='Say your country.', answers=["I'm from Spain"],
         explain='Never “I from …” — the am is obligatory.'),
    dict(kind='written', prompt='Say your name and your job.',
         answers=["I'm <name> and I'm a <job>", "I'm <name>. I'm a <job>", "My name is <name> and I'm a <job>"],
         explain='Use a before jobs: a teacher, a doctor.'),
])
add('en-foundations-u2-articles', [
    dict(kind='choice', prompt='I have ___ idea.', options=['an', 'a', 'the', 'some'], answers=['an'],
         explain='An before vowel sounds: an idea, an hour.'),
    dict(kind='gap', prompt='Look at ___ sun. It is hot.', answers=['the'],
         explain='There is one sun, so use the.'),
    dict(kind='order', prompt='Say what they do (plural).', answers=['They are teachers'],
         explain='Plural jobs take no article: they are teachers.'),
    dict(kind='written', prompt='Complete with two things: I need a ___ and an ___.',
         answers=['a <thing> and an <thing>', '<thing> and <thing>'],
         explain='a + consonant sound, an + vowel sound.'),
])
add('en-foundations-u2-this-that', [
    dict(kind='choice', prompt='___ is my bag. Here, in my hand.', options=['This', 'That', 'Those', 'It'], answers=['This'],
         explain='This points at something near you.'),
    dict(kind='gap', prompt='___ is your coat over there.', answers=['That'],
         explain='That points at something far away.'),
    dict(kind='order', prompt='Ask about this phone (near you).', answers=['Is this your phone'],
         explain='Is + this + the thing.'),
    dict(kind='written', prompt='Point at something near you and say what it is.',
         answers=['This is my <thing>', 'This is a <thing>', 'This is the <thing>'],
         explain='This is + my/a/the + thing.'),
])
add('en-foundations-u2-have-got', [
    dict(kind='choice', prompt='She ___ got two brothers.', options=['has', 'have', 'is', 'does'], answers=['has'],
         explain='With he/she/it: has got.'),
    dict(kind='gap', prompt='I ___ got a big family.', answers=['have'],
         explain='With I/you/we/they: have got.'),
    dict(kind='order', prompt='Ask about sisters.', answers=['Have you got any sisters'],
         explain='Have + you + got + any + plural.'),
    dict(kind='written', prompt='Say how many brothers or sisters you have got.',
         answers=["I've got <number> brothers and <number> sisters", "I've got <number> brother and <number> sister",
                  "I haven't got any brothers or sisters"],
         explain="I've got + number + family word."),
])
add('en-foundations-u3-present', [
    dict(kind='choice', prompt='They ___ in Madrid.', options=['live', 'lives', 'living', 'does live'], answers=['live'],
         explain='With they there is no -s: they live.'),
    dict(kind='gap', prompt='___ you work near here?', answers=['do'],
         explain='Questions with you need Do.'),
    dict(kind='order', prompt='Say you do not eat meat.', answers=["We don't eat meat"],
         explain="don't + base verb: We don't eat."),
    dict(kind='written', prompt='Say one thing you do every day.',
         answers=['I <verb> every day', 'Every day I <verb>'],
         explain='Present simple for routines: I work, I walk, I cook.'),
])
add('en-foundations-u3-third-person', [
    dict(kind='choice', prompt='He ___ football on Sundays.', options=['plays', 'play', 'playing', 'is play'], answers=['plays'],
         explain='With he/she/it the verb takes -s.'),
    dict(kind='gap', prompt='She ___ English at night. (study)', answers=['studies'],
         explain='Consonant + y → -ies: study → studies.'),
    dict(kind='order', prompt='Say where your sister works.', answers=['My sister works in a cafe'],
         explain='Sister is she → works with -s.'),
    dict(kind='written', prompt='Say that Ana speaks English.',
         answers=['Ana speaks English', 'She speaks English'],
         explain='Ana = she → speak + s.'),
])
add('en-foundations-u3-time', [
    dict(kind='choice', prompt="It's 7:30. It's half ___ seven.", options=['past', 'to', 'at', 'on'], answers=['past'],
         explain='Half past = y media.'),
    dict(kind='gap', prompt="I get up ___ seven o'clock.", answers=['at'],
         explain='Clock times take at: at seven.'),
    dict(kind='order', prompt='Say what comes after getting up.', answers=['Then I have breakfast'],
         explain='Then orders your routine.'),
    dict(kind='written', prompt='Say what time you usually get up.',
         answers=['I get up at <time>', 'I usually get up at <time>'],
         explain='get up at + time.'),
])
add('en-foundations-u4-can', [
    dict(kind='choice', prompt='___ you help me, please?', options=['Can', 'Are', 'Do', 'Is'], answers=['Can'],
         explain='Requests start with Can you …?'),
    dict(kind='gap', prompt="I ___ swim, but I can't dive.", answers=['can'],
         explain='can never takes -s and stays with the verb.'),
    dict(kind='order', prompt="Say your mother cannot drive.", answers=["She can't drive"],
         explain="can't + base verb: She can't drive."),
    dict(kind='written', prompt='Ask permission to open the window.',
         answers=['Can I open the window', 'Can I open the window, please'],
         explain='Can I …? asks for permission.'),
])
add('en-foundations-u4-there-is', [
    dict(kind='choice', prompt='___ a pharmacy near here?', options=['Is there', 'Are there', 'There is', 'Has'], answers=['Is there'],
         explain='Singular questions: Is there …?'),
    dict(kind='gap', prompt='There ___ two parks in my neighbourhood.', answers=['are'],
         explain='Plural things: there are.'),
    dict(kind='order', prompt='Say that a market exists on Saturdays.', answers=['There is a market on Saturdays'],
         explain='There is + singular thing.'),
    dict(kind='written', prompt='Say what there is in your kitchen.',
         answers=['There is <thing>', 'There are <things>', 'There is a <thing>'],
         explain='There is + one thing, there are + many.'),
])
add('en-foundations-u4-directions', [
    dict(kind='choice', prompt='Go ___ and then turn left.', options=['straight on', 'left', 'right', 'back'], answers=['straight on'],
         explain='Straight on = todo recto.'),
    dict(kind='gap', prompt='Turn left ___ the traffic lights.', answers=['at'],
         explain='Exact points take at: at the lights.'),
    dict(kind='order', prompt='Say where the bank is (beside).', answers=["It's next to the bank"],
         explain='next to = al lado de.'),
    dict(kind='written', prompt='Ask where the station is.',
         answers=['Where is the station', "Where's the station"],
         explain="Question word + is: Where is / Where's."),
])
add('en-foundations-u5-some-any', [
    dict(kind='choice', prompt="We haven't got ___ milk.", options=['any', 'some', 'a', 'many'], answers=['any'],
         explain='Negatives take any.'),
    dict(kind='gap', prompt='Can I have ___ water, please?', answers=['some'],
         explain='Requests take some, even as questions.'),
    dict(kind='order', prompt='Say you need sugar for the cake.', answers=['I need some sugar for the cake'],
         explain='Affirmatives take some.'),
    dict(kind='written', prompt='Say you need some bread.',
         answers=['I need some bread', "I need to buy some bread"],
         explain='some + uncountable: some bread, some water.'),
])
add('en-foundations-u5-likes', [
    dict(kind='choice', prompt='I love ___ to music.', options=['listening', 'listen', 'listens', 'to listening'], answers=['listening'],
         explain='Like/love/hate + verb-ing.'),
    dict(kind='gap', prompt='She hates ___ up early. (get)', answers=['getting'],
         explain='Short verb + consonant doubles: get → getting.'),
    dict(kind='order', prompt='Ask if the person enjoys cooking.', answers=['Do you like cooking'],
         explain='Do you like + -ing?'),
    dict(kind='written', prompt='Say one thing you love doing.',
         answers=['I love <verbing>', 'I love <verb>ing'],
         explain='I love + activity with -ing.'),
])
add('en-foundations-u5-cafe', [
    dict(kind='choice', prompt='___ like a coffee, please.', options=["I'd", 'I', 'Me', 'Mine'], answers=["I'd"],
         explain="I'd like = polite want."),
    dict(kind='gap', prompt='Can I have the ___ , please? (la cuenta)', answers=['bill'],
         explain='The bill (UK) / the check (US).'),
    dict(kind='order', prompt='Ask for the menu politely.', answers=['Can I have the menu, please'],
         explain='Can I have … , please? works everywhere.'),
    dict(kind='written', prompt='Order a tea politely.',
         answers=["Can I have a tea, please", "I'd like a tea, please", "Can I have a tea"],
         explain='Can I have / I’d like + drink + please.'),
])
add('en-foundations-u6-was-were', [
    dict(kind='choice', prompt='We ___ at home yesterday.', options=['were', 'was', 'are', 'did'], answers=['were'],
         explain='With we/you/they: were.'),
    dict(kind='gap', prompt='The film ___ great.', answers=['was'],
         explain='Singular things: was.'),
    dict(kind='order', prompt='Ask about last night.', answers=['Where were you last night'],
         explain='were + you for the past.'),
    dict(kind='written', prompt='Say the weather was good at the weekend.',
         answers=['The weather was good at the weekend', 'It was sunny at the weekend', 'The weather was nice at the weekend'],
         explain='was + adjective for past descriptions.'),
])
add('en-foundations-u6-past', [
    dict(kind='choice', prompt='I ___ TV last night.', options=['watched', 'watch', 'watching', 'am watch'], answers=['watched'],
         explain='Regular past: watch → watched.'),
    dict(kind='gap', prompt="She didn't ___ the email. (receive)", answers=['receive'],
         explain="After didn't the verb stays in base form."),
    dict(kind='order', prompt='Say they got here late yesterday.', answers=['They arrived late yesterday'],
         explain='arrive → arrived; no did when the past is on the verb.'),
    dict(kind='written', prompt='Say you worked on Saturday.',
         answers=['I worked on Saturday', 'On Saturday I worked'],
         explain='work → worked.'),
])
add('en-foundations-u6-weekend', [
    dict(kind='choice', prompt='___ you have a good weekend?', options=['Did', 'Was', 'Were', 'Do'], answers=['Did'],
         explain='have is a normal verb: questions need Did.'),
    dict(kind='gap', prompt='We ___ so tired on Sunday.', answers=['were'],
         explain='Past be with we: were.'),
    dict(kind='order', prompt='Start your Saturday story.', answers=['On Saturday I met my friends'],
         explain='Time anchor first: On Saturday …'),
    dict(kind='written', prompt='Tell one thing you did last weekend.',
         answers=['I <verbed> last weekend', 'Last weekend I <verbed>', 'On Saturday I <verbed>'],
         explain='Past verb + time anchor.'),
])


def insertion(exercises, indent):
    pad = ' ' * indent
    lines = [',']
    lines.append(pad + '"exercises": [')
    lines.append(',\n'.join(pad + '  ' + json.dumps(e, ensure_ascii=False) for e in exercises))
    lines.append(pad + ']')
    return '\n'.join(lines)


text = PATH.read_text()
data = json.loads(text)
lesson_ids = [lesson['id'] for unit in data['units'] for lesson in unit['lessons']]
assert set(lesson_ids) == set(E), (set(lesson_ids) ^ set(E))
for lesson_id in lesson_ids:
    marker = f'"id": "{lesson_id}"'
    start = text.index(marker)
    depth = 0
    close = None
    for i in range(start, len(text)):
        if text[i] == '{':
            depth += 1
        elif text[i] == '}':
            depth -= 1
            if depth < 0:
                close = i
                break
    assert close is not None, lesson_id
    assert '"exercises"' not in text[start:close], lesson_id
    prev = close - 1
    while text[prev] in ' \n\t':
        prev -= 1
    exercise_lines = ',\n'.join(' ' * 12 + json.dumps(e, ensure_ascii=False) for e in E[lesson_id])
    block = ',\n' + ' ' * 10 + '"exercises": [\n' + exercise_lines + '\n' + ' ' * 10 + ']\n' + ' ' * 8
    text = text[:prev + 1] + block + text[close:]
json.loads(text)
PATH.write_text(text + '\n' if not text.endswith('\n') else text)
print('Injected exercises for', len(lesson_ids), 'lessons')
