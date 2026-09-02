package com.lelloman.simpleaiassistant.tool

/** Validates the JSON-schema subset used by tool specifications. */
internal object ToolInputValidator {

    fun validate(spec: ToolSpec, input: Map<String, Any?>): String? =
        validateValue(input, spec.inputSchema, "input")

    private fun validateValue(value: Any?, schema: Map<String, Any?>, path: String): String? {
        val expectedType = schema["type"] as? String
        if (expectedType != null && !matchesType(value, expectedType)) {
            return "$path must be of type $expectedType"
        }

        val enumValues = schema["enum"] as? List<*>
        if (enumValues != null && enumValues.none { equivalent(it, value) }) {
            return "$path must be one of: ${enumValues.joinToString()}"
        }

        if (value is Map<*, *>) {
            val required = (schema["required"] as? List<*>)
                ?.filterIsInstance<String>()
                .orEmpty()
            val missing = required.filter { !value.containsKey(it) }
            if (missing.isNotEmpty()) {
                return "$path is missing required properties: ${missing.joinToString()}"
            }

            @Suppress("UNCHECKED_CAST")
            val properties = schema["properties"] as? Map<String, Any?> ?: emptyMap()
            if (schema["additionalProperties"] == false) {
                val unexpected = value.keys.filterIsInstance<String>().filter { it !in properties }
                if (unexpected.isNotEmpty()) {
                    return "$path contains unexpected properties: ${unexpected.joinToString()}"
                }
            }

            for ((name, propertyValue) in value) {
                if (name !is String) continue
                @Suppress("UNCHECKED_CAST")
                val propertySchema = properties[name] as? Map<String, Any?> ?: continue
                validateValue(propertyValue, propertySchema, "$path.$name")?.let { return it }
            }
        }

        if (value is List<*>) {
            @Suppress("UNCHECKED_CAST")
            val itemSchema = schema["items"] as? Map<String, Any?>
            if (itemSchema != null) {
                value.forEachIndexed { index, item ->
                    validateValue(item, itemSchema, "$path[$index]")?.let { return it }
                }
            }
        }

        return null
    }

    private fun matchesType(value: Any?, type: String): Boolean = when (type) {
        "null" -> value == null
        "string" -> value is String
        "boolean" -> value is Boolean
        "number" -> value is Number
        "integer" -> value is Byte || value is Short || value is Int || value is Long ||
            (value is Float && value.isFinite() && value % 1f == 0f) ||
            (value is Double && value.isFinite() && value % 1.0 == 0.0)
        "object" -> value is Map<*, *>
        "array" -> value is List<*>
        else -> true
    }

    private fun equivalent(left: Any?, right: Any?): Boolean =
        if (left is Number && right is Number) left.toDouble() == right.toDouble() else left == right
}
