package com.varabyte.kobweb.gradle.application.templates

import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FileSpec
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.withIndent
import com.varabyte.kobweb.common.navigation.RoutePrefix
import com.varabyte.kobweb.gradle.application.BuildTarget
import com.varabyte.kobweb.gradle.application.extensions.AppBlock
import com.varabyte.kobweb.project.frontend.AppData
import com.varabyte.kobweb.project.frontend.FrontendData
import com.varabyte.kobweb.project.frontend.merge
import org.gradle.api.GradleException

private const val KOBWEB_GROUP = "com.varabyte.kobweb"

enum class SilkSupport {
    NONE,
    FOUNDATION,
    FULL,
}

fun createMainFunction(
    appData: AppData,
    libData: List<FrontendData>,
    silkSupport: SilkSupport,
    appBlock: AppBlock,
    routePrefix: RoutePrefix,
    target: BuildTarget
): String {
    val usingSilkFoundation = silkSupport != SilkSupport.NONE
    val usingSilkWidgets = silkSupport == SilkSupport.FULL

    val appFqn = appData.appEntry?.fqn
        ?: (KOBWEB_GROUP + if (usingSilkWidgets) ".silk.SilkApp" else ".core.KobwebApp")

    val frontendData = (listOf(appData.frontendData) + libData).merge(throwError = { throw GradleException(it) })
    val fileBuilder = FileSpec.builder("", "main").indent(" ".repeat(4))

    buildList {
        val defaultImports = listOf(
            "androidx.compose.runtime.CompositionLocalProvider",
            "$KOBWEB_GROUP.core.AppGlobals",
            "$KOBWEB_GROUP.navigation.RoutePrefix",
            "$KOBWEB_GROUP.navigation.Router",
            "$KOBWEB_GROUP.navigation.UpdateHistoryMode",
            "kotlinx.browser.document",
            "kotlinx.browser.window",
            "org.jetbrains.compose.web.renderComposable",
        )
        addAll(defaultImports)
        if (target == BuildTarget.DEBUG) {
            val debugImports = listOf(
                "$KOBWEB_GROUP.browser.api",
                "kotlinx.dom.hasClass",
                "kotlinx.dom.removeClass",
                "org.w3c.dom.EventSource",
                "org.w3c.dom.EventSourceInit",
                "org.w3c.dom.MessageEvent",
                "org.w3c.dom.get",
            )
            addAll(debugImports)
        }
        if (frontendData.kobwebInits.any { it.acceptsContext }) {
            add("$KOBWEB_GROUP.core.init.InitKobwebContext")
        }
        if (frontendData.keyframesList.isNotEmpty()) {
            add("$KOBWEB_GROUP.silk.components.animation.registerKeyframes")
        }
        if (usingSilkFoundation) {
            add("$KOBWEB_GROUP.silk.defer.renderWithDeferred")
        }
    }.sorted().forEach { import ->
        fileBuilder.addImport(import.substringBeforeLast('.'), import.substringAfterLast('.'))
    }

    // region debug-only functions
    if (target == BuildTarget.DEBUG) {
        fileBuilder.addFunction(
            FunSpec.builder("forceReloadNow")
                .addModifiers(KModifier.PRIVATE)
                .addCode(
                    """
                        window.stop()
                        window.location.reload()
                    """.trimIndent()
                )
                .build()
        )

        fileBuilder.addFunction(
            FunSpec.builder("handleServerStatusEvents")
                .addModifiers(KModifier.PRIVATE)
                .addCode(
                    """
                        val status = document.getElementById("status")!!
                        var lastVersion: Int? = null
                        var shouldReload = false
    
                        val warningIcon = status.children[0]!!
                        val spinnerIcon = status.children[1]!!
                        val statusText = status.children[2]!!
        
                        status.addEventListener("transitionend", {
                            if (status.hasClass("fade-out")) {
                                status.removeClass("fade-out")
                                if (shouldReload) {
                                    forceReloadNow()
                                }
                            }
                        })
    
                        val eventSource = EventSource("/api/kobweb-status", EventSourceInit(true))
                        eventSource.addEventListener("version", { evt ->
                            val version = (evt as MessageEvent).data.toString().toInt()
                            if (lastVersion == null) {
                                lastVersion = version
                            }
                            if (lastVersion != version) {
                                lastVersion = version
                                if (status.className.isNotEmpty()) {
                                    shouldReload = true
                                } else {
                                    // Not sure if we can get here but if we can't rely on the status transition
                                    // to reload we should do it ourselves.
                                    forceReloadNow()
                                }
                            }
                        })
    
                        eventSource.addEventListener("status", { evt ->
                            val values: dynamic = JSON.parse<Any>((evt as MessageEvent).data.toString())
                            val text = values.text as String
                            val isError = (values.isError as String).toBoolean()
                            if (text.isNotBlank()) {
                                warningIcon.className = if (isError) "visible" else "hidden"
                                spinnerIcon.className = if (isError) "hidden" else "visible"
                                statusText.innerHTML = "<i>${'$'}text</i>"
                                status.className = "fade-in"
                            } else {
                                if (status.className == "fade-in") {
                                    status.className = "fade-out"
                                }
                            }
                        })
    
                        eventSource.onerror = { eventSource.close() }
                    """.trimIndent()
                ).build()
        )
    }
    // endregion

    fileBuilder.addFunction(
        FunSpec.builder("main").apply {
            if (target == BuildTarget.DEBUG) {
                addStatement("handleServerStatusEvents()")
                addStatement("")
                addStatement("window.api.logOnError = true")
                addStatement("")
            }

            addCode(CodeBlock.builder().apply {
                addStatement("RoutePrefix.set(\"$routePrefix\")")
                addStatement("val router = Router()")
                addStatement("$KOBWEB_GROUP.core.init.initKobweb(router) { ctx ->")
                withIndent {
                    frontendData.pages.sortedBy { it.route }.forEach { entry ->
                        addStatement("""ctx.router.register("${entry.route}") { ${entry.fqn}() }""")
                    }
                    addStatement("")

                    frontendData.kobwebInits.forEach { entry ->
                        val ctx = if (entry.acceptsContext) "ctx" else ""
                        addStatement("${entry.fqn}($ctx)")
                    }
                }
                addStatement("}")
                if (appBlock.cleanUrls.get()) {
                    addStatement("router.addRouteInterceptor {")
                    withIndent {
                        addStatement("path = path.removeSuffix(\".html\").removeSuffix(\".htm\")")
                    }
                    addStatement("}")
                }
                addStatement("")
            }.build())

            if (usingSilkFoundation) {
                addCode(CodeBlock.builder().apply {
                    addStatement("$KOBWEB_GROUP.silk.init.additionalSilkInitialization = { ctx ->")
                    withIndent {
                        if (usingSilkWidgets) {
                            addStatement("com.varabyte.kobweb.silk.init.initSilkWidgets(ctx)")
                            addStatement("com.varabyte.kobweb.silk.init.initSilkWidgetsKobweb(ctx)")
                        }
                        frontendData.silkStyles.forEach { entry ->
                            addStatement("ctx.theme.registerComponentStyle(${entry.fqcn})")
                        }
                        frontendData.silkVariants.forEach { entry ->
                            addStatement("ctx.theme.registerComponentVariants(${entry.fqcn})")
                        }
                        frontendData.keyframesList.forEach { entry ->
                            addStatement("ctx.stylesheet.registerKeyframes(${entry.fqcn})")
                        }
                        frontendData.silkInits.forEach { init ->
                            addStatement("${init.fqn}(ctx)")
                        }
                    }
                    addStatement("}")
                }.build())
                addStatement("")
            }

            val appGlobals = appBlock.globals.get()

            // Note: Below, we use %S when specifying key/value pairs. This prevents KotlinPoet from breaking
            // our text in the middle of a String.
            addCode(CodeBlock.Builder().apply {
                addStatement("router.navigateTo(window.location.href.removePrefix(window.location.origin), UpdateHistoryMode.REPLACE)")
                addStatement("")
                addComment("For SEO, we may bake the contents of a page in at build time. However, we will")
                addComment("overwrite them the first time we render this page with their composable, dynamic")
                addComment("versions. Think of this as poor man's hydration :)")
                addComment("See also: https://en.wikipedia.org/wiki/Hydration_(web_development)")
                addStatement("val root = document.getElementById(\"root\")!!")
                addStatement("while (root.firstChild != null) { root.removeChild(root.firstChild!!) }")
                addStatement("")
                addStatement(
                    "AppGlobals.initialize(mapOf(${Array(appGlobals.size) { "%S to %S" }.joinToString()}))",
                    *appGlobals.flatMap { entry -> listOf(entry.key, entry.value) }.toTypedArray()
                )
                addStatement("renderComposable(rootElementId = \"root\") {")
                withIndent {
                    addStatement("$appFqn {")
                    withIndent {
                        if (usingSilkFoundation) {
                            addStatement("router.renderActivePage { renderWithDeferred { it() } }")
                        } else {
                            addStatement("router.renderActivePage()")
                        }
                    }
                    addStatement("}")
                }
                addStatement("}")
            }.build())
        }.build()
    )

    return fileBuilder.build().toString()
}
