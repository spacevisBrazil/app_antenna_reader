package com.uhflogger.filter

/**
 * Camada 1 do filtro: verifica se um EPC pertence a uma das famílias
 * configuradas pelo usuário. Padrões são strings hex do mesmo tamanho do EPC,
 * onde 'X' marca posições que aceitam qualquer dígito hex e as demais
 * posições precisam bater exatamente (ex.: "0000100000000XXX").
 */
object EpcFamilyMatcher {

    /** Lista vazia (sem padrões configurados) = não filtra nada — passa tudo. */
    fun isAllowed(epc: String, patternsCsv: String): Boolean {
        val patterns = parsePatterns(patternsCsv)
        if (patterns.isEmpty()) return true
        return patterns.any { matches(epc, it) }
    }

    fun parsePatterns(patternsCsv: String): List<String> =
        patternsCsv.split(",")
            .map { it.trim().uppercase() }
            .filter { it.isNotEmpty() }

    fun matches(epc: String, pattern: String): Boolean {
        val e = epc.uppercase()
        if (e.length != pattern.length) return false
        for (i in pattern.indices) {
            val p = pattern[i]
            if (p == 'X') continue
            if (p != e[i]) return false
        }
        return true
    }
}
