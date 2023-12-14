package com.varabyte.kobweb.compose.css

import org.jetbrains.compose.web.attributes.AttrsScope

fun AttrsScope<*>.disabled(value: Boolean = true) {
    attr("disabled", value.toString())
}
