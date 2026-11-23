package com.jarvis.app

import org.json.JSONObject

/**
 * Hands mode: for tasks without a ready action ("зайди в настройки и включи тёмную тему").
 * Each step Jarvis reads the screen as a numbered list, the model picks ONE move, Jarvis does it, and repeats.
 * The grammar only allows numbers that exist on the current screen.
 */
object HandsBrain {
    private const val SYS = """Ты Джарвис и управляешь телефоном за пользователя. На каждом шаге ты видишь экран списком элементов с номерами и выбираешь ОДНО действие.
Ответ только JSON, один из вариантов:
{"do":"tap","n":номер} нажать элемент
{"do":"type","n":номер,"text":"текст"} ввести текст в поле
{"do":"scroll","dir":"down"} или "up" пролистать
{"do":"back"} назад
{"do":"open_app","name":"название"} открыть приложение
{"do":"done","say":"что сделано"} цель достигнута или сделать нельзя
Не повторяй одно и то же действие, если экран не меняется. Если в «Сделано» уже есть нужное действие и на экране виден результат, отвечай done."""

    private const val EX_USER = """Цель: открой настройки Wi-Fi
Сделано: open_app Настройки
Приложение: com.android.settings
Экран:
[1] текст «Настройки»
[2] кнопка «Сеть и Интернет»
[3] кнопка «Подключенные устройства»
[4] кнопка «Приложения»"""
    private const val EX_ANS = """{"do":"tap","n":2}"""

    fun prompt(goal: String, app: String, screen: List<String>, done: List<String>): String {
        val user = "Цель: $goal\nСделано: " + (if (done.isEmpty()) "ничего" else done.takeLast(6).joinToString("; ")) +
            "\nПриложение: $app\nЭкран:\n" + screen.withIndex().joinToString("\n") { "[${it.index + 1}] ${it.value}" }
        return "<|im_start|>system\n$SYS<|im_end|>\n" +
            "<|im_start|>user\n$EX_USER<|im_end|>\n<|im_start|>assistant\n$EX_ANS<|im_end|>\n" +
            "<|im_start|>user\n$user<|im_end|>\n<|im_start|>assistant\n"
    }

    fun grammar(count: Int): String {
        val num = if (count <= 0) "\"0\"" else (1..count).joinToString(" | ") { "\"$it\"" }
        val numRules = if (count <= 0) "" else
            "tap ::= \"{\\\"do\\\":\\\"tap\\\",\\\"n\\\":\" num \"}\"\n" +
            "type ::= \"{\\\"do\\\":\\\"type\\\",\\\"n\\\":\" num \",\\\"text\\\":\" str \"}\"\n"
        val alts = (if (count > 0) "tap | type | " else "") + "scroll | back | open | done"
        return """root ::= $alts
${numRules}scroll ::= "{\"do\":\"scroll\",\"dir\":" ("\"up\"" | "\"down\"") "}"
back ::= "{\"do\":\"back\"}"
open ::= "{\"do\":\"open_app\",\"name\":" str "}"
done ::= "{\"do\":\"done\",\"say\":" str "}"
num ::= $num
str ::= "\"" chr{0,80} "\""
chr ::= [^"\\\x00-\x1f] | "\\" ["\\nt]
"""
    }

    fun parse(json: String): JSONObject = JSONObject(json)
}

