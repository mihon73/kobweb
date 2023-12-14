package com.varabyte.kobweb.gradle.application.tasks

import com.microsoft.playwright.Browser
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.TimeoutError
import com.microsoft.playwright.Tracing
import com.varabyte.kobweb.common.navigation.RoutePrefix
import com.varabyte.kobweb.gradle.application.extensions.AppBlock
import com.varabyte.kobweb.gradle.application.extensions.app
import com.varabyte.kobweb.gradle.application.extensions.export
import com.varabyte.kobweb.gradle.application.util.PlaywrightCache
import com.varabyte.kobweb.gradle.core.extensions.KobwebBlock
import com.varabyte.kobweb.gradle.core.kmp.jsTarget
import com.varabyte.kobweb.gradle.core.tasks.KobwebModuleTask
import com.varabyte.kobweb.gradle.core.util.searchZipFor
import com.varabyte.kobweb.ksp.KOBWEB_METADATA_FRONTEND
import com.varabyte.kobweb.project.conf.KobwebConf
import com.varabyte.kobweb.project.frontend.AppData
import com.varabyte.kobweb.project.frontend.FrontendData
import com.varabyte.kobweb.project.frontend.merge
import com.varabyte.kobweb.server.api.ServerStateFile
import com.varabyte.kobweb.server.api.SiteLayout
import kotlinx.serialization.json.Json
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.jsoup.Jsoup
import java.io.File
import javax.inject.Inject
import kotlin.io.path.writeText
import kotlin.system.measureTimeMillis
import kotlin.time.DurationUnit
import com.varabyte.kobweb.gradle.application.Browser as KobwebBrowser

abstract class KobwebExportTask @Inject constructor(
    private val kobwebConf: KobwebConf,
    kobwebBlock: KobwebBlock,
    private val siteLayout: SiteLayout
) : KobwebModuleTask(kobwebBlock, "Export the Kobweb project into a static site") {

    @InputFiles
    fun getCompileClasspath() = project.configurations.named(project.jsTarget.compileClasspath)

    @get:InputFile
    abstract val appFrontendMetadataFile: RegularFileProperty

    @OutputDirectory
    fun getSiteDir(): File {
        return project.layout.projectDirectory.dir(kobwebConf.server.files.prod.siteRoot).asFile
    }

    private fun Page.takeSnapshot(url: String): String {
        onPageError {
            logger.error(
                "e: Route \"/${
                    url.removePrefix("http://").substringAfter('/')
                }\" crashed mid-export. You should investigate this by using `kobweb run` and visiting that route."
            )
        }
        navigate("${url}?_kobwebIsExporting=true")

        // First, we bake dynamic styles into static ones. Let me explain :)
        // Compose HTML creates empty style nodes and then adds styles to them programmatically, meaning the page
        // works right but if you go to inspect the DOM using debugging tools or save the page, all you see is an empty
        // style tag and the information is lost.
        // By iterating over those style nodes and explicitly overwriting them with their own values, we can then save
        // the page with filled out style tags. This ensures that when a user first downloads the page, that things look
        // right even before the javascript is downloaded. When the javascript runs, it will simply clear our baked in
        // styles and replace them with the programmatic ones (but users won't be able to tell because the values should
        // be the same).
        // If we didn't do this, then what would happen is the user would download the page, see raw text unadorned
        // without any styles, and then after a brief period of time (depending on download speeds) styles would pop
        // in, quite jarringly.
        evaluate(
            """
                for (let s = 0; s < document.styleSheets.length; s++) {
                    var stylesheet = document.styleSheets[s]
                    stylesheet = stylesheet instanceof CSSStyleSheet ? stylesheet : null;

                    // Trying to peek at external stylesheets causes a security exception so step over them
                    if (stylesheet != null && stylesheet.href == null) {
                        var styleNode = stylesheet.ownerNode
                        styleNode = styleNode instanceof Element ? styleNode : null
                        if (styleNode != null && styleNode.innerHTML == '') {
                            const rules = []
                            for (let r = 0; r < stylesheet.cssRules.length; ++r) {
                                rules.push(stylesheet.cssRules[r].cssText.replace(/(\n)/gm, ''))
                            }
                            styleNode.innerHTML = rules.join('')
                        }
                    }
                }
            """.trimIndent()
        )

        // Use Jsoup for pretty printing
        return Jsoup.parse(content()).toString()
    }

    private fun Browser.takeSnapshot(route: String, url: String): String {
        newContext().use { context ->
            kobwebBlock.app.export.timeout.orNull?.let { context.setDefaultTimeout(it.toDouble(DurationUnit.MILLISECONDS)) }
            val traceConfig = kobwebBlock.app.export.traceConfig.orNull
                ?.takeIf { it.filter(route) }
            if (traceConfig != null) {
                val traceRoot = traceConfig.root
                traceRoot.toFile().mkdirs()
                traceRoot.resolve("README.md").writeText(
                    """
                        # Export Traces

                        This directory contains traces of your exported pages. These traces can be opened in the
                        [Playwright Trace Viewer](https://trace.playwright.dev/).

                        To open a trace, open the link above and then drag and drop it onto that page.

                        For understanding trace results, see: https://playwright.dev/docs/trace-viewer
                    """.trimIndent()
                )

                context.tracing().start(
                    Tracing.StartOptions()
                        .setTitle(route)
                        .setScreenshots(traceConfig.includeScreenshots)
                        .setSnapshots(true)
                        .setSources(true)
                )
            }
            context.newPage().use { page ->
                try {
                    return page.takeSnapshot(url)
                } finally {
                    traceConfig?.let { traceConfig ->
                        val traceRelativePath = traceConfig.root.resolve(
                            (if (route.endsWith('/')) route + "index" else route).removePrefix(
                                "/"
                            ) + ".trace.zip"
                        )
                        context.tracing().stop(Tracing.StopOptions().setPath(traceRelativePath))
                        logger.lifecycle("Saved export trace to: $traceRelativePath")
                    }
                }
            }
        }
    }

    private fun <T> T.toTriple() = Triple(this, this, this)
    private fun <T, S> Triple<T, T, T>.map(transform: (T) -> S) =
        Triple(transform(first), transform(second), transform(third))

    @TaskAction
    fun execute() {
        // Sever should be running since "kobwebStart" is a prerequisite for this task
        val port = ServerStateFile(kobwebApplication.kobwebFolder).content!!.port

        val appData = Json.decodeFromString<AppData>(appFrontendMetadataFile.get().asFile.readText())
        val frontendData = buildList {
            add(appData.frontendData)
            getCompileClasspath().get().files.forEach { file ->
                file.searchZipFor(KOBWEB_METADATA_FRONTEND) { bytes ->
                    add(Json.decodeFromString<FrontendData>(bytes.decodeToString()))
                }
            }
        }
            .merge(throwError = { throw GradleException(it) })
            .also { data ->
                data.pages.toList().let { entries ->
                    if (entries.isEmpty()) {
                        throw GradleException("No pages were defined. You must tag at least one page with the `@Page` annotation!")
                    } else if (entries.none { it.route == "/" }) {
                        throw GradleException(
                            "No root route was defined for your site. This means if people visit your website URL, they'll get a 404 error. Create a `@Page` in a root `pages/Index.kt` file to make this warning go away."
                        )
                    }
                }
            }

        val (pagesRoot, resourcesRoot, systemRoot) = when (siteLayout) {
            SiteLayout.KOBWEB -> Triple("pages", "resources", "system").map { getSiteDir().resolve(it) }
            SiteLayout.STATIC -> getSiteDir().toTriple()
        }

        frontendData.pages.takeIf { it.isNotEmpty() }?.let { pages ->
            val browser = kobwebBlock.app.export.browser.get()
            PlaywrightCache().install(browser)
            Playwright.create(
                Playwright.CreateOptions().setEnv(
                    mapOf(
                        // Should have been downloaded above, by PlaywrightCache()
                        "PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD" to "1"
                    )
                )
            ).use { playwright ->
                val browserType = when (browser) {
                    KobwebBrowser.Chromium -> playwright.chromium()
                    KobwebBrowser.Firefox -> playwright.firefox()
                    KobwebBrowser.WebKit -> playwright.webkit()
                }
                browserType.launch().use { browser ->
                    val routePrefix = RoutePrefix(kobwebConf.site.routePrefix)
                    pages
                        .asSequence()
                        .map { it.route }
                        // Skip export routes with dynamic parts, as they are dynamically generated based on their URL
                        // anyway
                        .filter { !it.contains('{') }
                        .filter { route ->
                            val ctx = AppBlock.ExportBlock.ExportFilterContext(route)
                            (kobwebBlock.app.export.filter.orNull?.invoke(ctx) ?: true)
                                .also { shouldExport ->
                                    if (!shouldExport) {
                                        logger.lifecycle("\nSkipped export for \"$route\".")
                                    }
                                }
                        }
                        .map { route -> AppBlock.ExportBlock.RouteConfig(route) }
                        .toSet()
                        .let { pageRoutes ->
                            pageRoutes + kobwebBlock.app.export.extraRoutes.orNull.orEmpty()
                        }
                        .takeIf { routes -> routes.isNotEmpty() }
                        ?.forEach { routeConfig ->
                            val route = routeConfig.route
                            logger.lifecycle("\nSnapshotting html for \"$route\"...")

                            val prefixedRoute = routePrefix.prependTo(route)

                            try {
                                val snapshot: String
                                val elapsedMs = measureTimeMillis {
                                    snapshot = browser.takeSnapshot(route, "http://localhost:$port$prefixedRoute")
                                }

                                pagesRoot
                                    .resolve(routeConfig.exportPath)
                                    .run {
                                        if (this.exists()) {
                                            logger.warn("w: Export for \"${routeConfig.route}\" overwrote existing file \"$this\".")
                                        }

                                        parentFile.mkdirs()
                                        writeText(snapshot)
                                    }

                                logger.lifecycle("Snapshot finished in ${elapsedMs}ms (saved to: \"${routeConfig.exportPath}\").")
                            } catch (ex: TimeoutError) {
                                logger.error(buildString {
                                    append("e: Export for \"${routeConfig.route}\" skipped due to timeout.")
                                    if (siteLayout == SiteLayout.KOBWEB) {
                                        append(" It might be worth reviewing if any of your API routes have blocking logic in them (e.g. a database failing to connect), as this can eventually cause the Kobweb server to hang if too many blocking calls accumulate.")
                                    }
                                    append(" In your build script, consider calling `kobweb.app.export.enableTraces(...)` to generate snapshots which can help understanding. Finally, you can try increasing the timeout by setting `kobweb.app.export.timeout`.")
                                })
                            }
                        }
                        ?: run {
                            val noPagesExportedMessage = buildString {
                                append("No pages were found to export.")
                                if (kobwebBlock.app.export.filter.isPresent) {
                                    append(" This may be because your build script's `kobweb.app.export.filter` is filtering out all pages.")
                                }
                            }
                            // This case is an error in static layout mode, because with no pages, there's nothing for
                            // the user to visit. For a kobweb layout, however, there is always at least a minimal
                            // index.html file included.
                            when (siteLayout) {
                                SiteLayout.KOBWEB -> logger.warn("w: $noPagesExportedMessage")
                                SiteLayout.STATIC -> logger.error("e: $noPagesExportedMessage")
                            }
                        }
                }
            }
        }

        // Copy resources.
        // Note: The "index.html" file that comes from here is auto-generated and useful as a fallback for dynamic
        // export layouts but shouldn't be copied over in static layouts as those should only include pages explicitly
        // defined by the site.
        getResourceFilesJsWithRoots().forEach { rootAndFile ->
            // Drop the leading slash so we don't confuse File resolve logic
            val relativePath = rootAndFile.relativeFile.invariantSeparatorsPath.substringAfter(getPublicPath()).drop(1)
            if (relativePath == "index.html" && siteLayout != SiteLayout.KOBWEB) return@forEach

            (if (relativePath != "index.html") resourcesRoot else systemRoot)
                .resolve(relativePath)
                .let { destFile ->
                    rootAndFile.file.copyTo(destFile, overwrite = true)
                }
        }

        val scriptFileStr = kobwebConf.server.files.prod.script
        val scriptFile = project.layout.projectDirectory.file(scriptFileStr).asFile
        if (!scriptFile.exists()) {
            throw GradleException(
                "e: Your .kobweb/conf.yaml prod script (\"$scriptFileStr\") could not be found. This must be fixed before exporting. Perhaps search your build/ directory for \"${
                    scriptFileStr.substringAfterLast(
                        '/'
                    )
                }\" to find the right path."
            )
        }

        run {
            val destFile = systemRoot.resolve(scriptFile.name)
            scriptFile.copyTo(destFile, overwrite = true)
        }

        if (kobwebBlock.app.export.includeSourceMap.get()) {
            val scriptMapFile = File("${scriptFile}.map")
            val destFile = systemRoot.resolve(scriptMapFile.name)
            scriptMapFile.copyTo(destFile, overwrite = true)
        }

        // API routes are only supported by the Kobweb layout
        if (siteLayout == SiteLayout.KOBWEB) {
            // The api.jar is not guaranteed to exist -- not every project needs to have API routes defined.
            kobwebConf.server.files.dev.api?.let { apiFile ->
                val apiJarFile = project.layout.projectDirectory.file(apiFile).asFile
                if (apiJarFile.exists()) {
                    val destFile = systemRoot.resolve(apiJarFile.name)
                    apiJarFile.copyTo(destFile, overwrite = true)
                }
            }
        }
    }
}
