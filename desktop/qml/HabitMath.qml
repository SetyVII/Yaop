pragma Singleton
import QtQuick

// Habit maths (spec §24), computed on display only. Mirrors mobile HabitMath.
QtObject {
    function iso(d) { return Qt.formatDate(d, "yyyy-MM-dd") }
    function code(d) { return ["sun","mon","tue","wed","thu","fri","sat"][d.getDay()] }
    function kind(h) { return h.kind || "check" }
    function goal(h) { return Math.max(1, Number(h.goal || 1)) }
    function isDue(h, d) {
        if ((h.state || "active") !== "active") return false
        if (h.start && iso(d) < String(h.start).substring(0, 10)) return false
        if ((h.freq || "daily") === "weekdays") return (h.days || []).indexOf(code(d)) >= 0
        return true
    }
    function isSkip(h, d) { return !!h && !!h.rec && h.rec[iso(d)] === "skip" }
    function progress(h, d) {
        if (!h || !h.rec) return 0
        const v = h.rec[iso(d)]
        if (v === undefined || v === null || v === false) return 0
        if (v === "skip") return 1
        if (kind(h) === "check") return 1
        return Math.min(1, Number(v) / goal(h))
    }
    function met(h, d) { return progress(h, d) >= 1 && !isSkip(h, d) }
    function streak(h) {
        const today = new Date(); today.setHours(12)
        let n = met(h, today) ? 1 : 0
        for (let i = 1; i < 3650; i++) {
            const d = new Date(today.getTime() - i * 864e5)
            if (h.start && iso(d) < String(h.start).substring(0, 10)) break
            if (!isDue(h, d) || isSkip(h, d)) continue
            if (!met(h, d)) break
            n++
        }
        return n
    }
    function rate30(h) {
        const today = new Date(); today.setHours(12)
        let due = 0, ok = 0
        for (let i = 0; i < 30; i++) {
            const d = new Date(today.getTime() - i * 864e5)
            if (!isDue(h, d) || isSkip(h, d)) continue
            due++; if (met(h, d)) ok++
        }
        return due === 0 ? 0 : Math.round(100 * ok / due)
    }
    function tap(h, d) {
        const key = iso(d)
        if (kind(h) === "check") ws.setHabitDay(h.id, key, met(h, d) ? null : true)
        else {
            const cur = Number(h.rec[key] || 0)
            ws.setHabitDay(h.id, key, cur >= goal(h) ? null : cur + 1)
        }
    }
    function find(id) {
        const all = ws ? ws.habits() : []
        for (let i = 0; i < all.length; i++) if (all[i].id === id) return all[i]
        return null
    }
}
