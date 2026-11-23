package com.jarvis.app

import android.os.SystemClock

/** Runs hands mode on the real screen through the accessibility service. */
class Hands(private val llm: () -> Llm, private val actions: Actions, private val maxSteps: () -> Int, private val tooSmall: () -> Boolean) {
    @Volatile var stopped = false

    fun run(goal: String, log: (String) -> Unit): String {
        val acc = JarvisAccess.instance ?: return "Для режима «руки» включи Джарвиса в Спец. возможностях."
        if (tooSmall()) return "Модель 0.5b для режима «руки» слишком слабая. Скачай 1.5b или подключи Ollama на ПК."
        stopped = false
        val done = mutableListOf<String>()
        var last = ""; var same = 0
        var toggled: String? = null      // label of the switch tapped on the previous step
        val g = goal.lowercase()
        val wantOn = "включи" in g
        val wantOff = "выключи" in g || "отключи" in g
        for (step in 1..maxSteps()) {
            if (stopped) return "Остановлено."
            SystemClock.sleep(if (step == 1) 400 else 1200)
            val els = acc.snapshot()
            // a small model often taps the same switch again and turns it back off; check the switch itself
            toggled?.let { lbl ->
                val sw = els.firstOrNull { it.label == lbl && it.kind.startsWith("переключатель") }
                if (sw != null && ((wantOn && sw.kind.endsWith("вкл")) || (wantOff && sw.kind.endsWith("выкл"))))
                    return "Готово: «$lbl» " + if (wantOn) "включено." else "выключено."
            }
            toggled = null
            val lines = els.map { "${it.kind} «${it.label}»".replace(" «»", "") }
            val raw = llm().gen(HandsBrain.prompt(goal, acc.currentApp(), lines, done), HandsBrain.grammar(els.size), 120)
            val o = HandsBrain.parse(raw)
            val what = o.optString("do")
            val desc = when (what) {
                "tap" -> "tap ${els.getOrNull(o.optInt("n") - 1)?.label ?: o.optInt("n")}"
                "type" -> "type «${o.optString("text")}»"
                "scroll" -> "scroll ${o.optString("dir")}"
                "open_app" -> "open_app ${o.optString("name")}"
                else -> what
            }
            if (desc == last) same++ else { same = 0; last = desc }
            if (same >= 2) return "Застрял на одном действии ($desc). Попробуй сказать задачу проще."
            log("🖐 $step: $desc")
            when (what) {
                "done" -> return o.optString("say").ifBlank { "Готово." }
                "tap" -> els.getOrNull(o.optInt("n") - 1)?.let {
                    acc.click(it.node)
                    if (it.kind.startsWith("переключатель")) toggled = it.label
                }
                "type" -> els.getOrNull(o.optInt("n") - 1)?.let { acc.setText(it.node, o.optString("text")) }
                "scroll" -> acc.scroll(o.optString("dir"))
                "back" -> acc.global("back")
                "open_app" -> actions.run(Step("open_app", o.optString("name")))
            }
            done.add(desc)
        }
        return "Сделал ${maxSteps()} шагов, но не уверен, что цель достигнута."
    }
}
