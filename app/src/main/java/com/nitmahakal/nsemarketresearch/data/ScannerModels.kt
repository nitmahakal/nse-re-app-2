package com.nitmahakal.nsemarketresearch.data

data class IndicatorSpec(
    val name: String,
    val params: LinkedHashMap<String, String> = linkedMapOf()
) {
    fun toJson(): org.json.JSONObject = org.json.JSONObject().apply {
        put("name", name)
        put("params", org.json.JSONObject(params as Map<*, *>))
    }
}

data class ConditionDraft(
    var left: IndicatorSpec,
    var comparator: String,
    var right: IndicatorSpec,
    var logic: String = "AND"
) {
    fun toJson(): org.json.JSONObject = org.json.JSONObject().apply {
        put("left", left.toJson())
        put("comparator", comparator)
        put("right", right.toJson())
        put("logic", logic)
    }
}
