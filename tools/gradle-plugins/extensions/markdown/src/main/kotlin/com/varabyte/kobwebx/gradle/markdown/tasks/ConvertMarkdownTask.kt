package com.varabyte.kobwebx.gradle.markdown.tasks

import com.varabyte.kobweb.common.lang.packageConcat
import com.varabyte.kobweb.common.lang.toPackageName
import com.varabyte.kobweb.gradle.core.extensions.KobwebBlock
import com.varabyte.kobweb.gradle.core.kmp.jsTarget
import com.varabyte.kobweb.gradle.core.util.LoggingReporter
import com.varabyte.kobweb.gradle.core.util.RootAndFile
import com.varabyte.kobweb.gradle.core.util.getResourceFilesWithRoots
import com.varabyte.kobweb.gradle.core.util.getResourceRoots
import com.varabyte.kobweb.gradle.core.util.prefixQualifiedPackage
import com.varabyte.kobwebx.gradle.markdown.KotlinRenderer
import com.varabyte.kobwebx.gradle.markdown.MarkdownBlock
import com.varabyte.kobwebx.gradle.markdown.MarkdownFeatures
import com.varabyte.kobwebx.gradle.markdown.MarkdownHandlers
import org.commonmark.node.Node
import org.commonmark.parser.Parser
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.getByType
import java.io.File
import java.io.IOException
import javax.inject.Inject

abstract class ConvertMarkdownTask @Inject constructor(
    private val kobwebBlock: KobwebBlock,
    private val markdownBlock: MarkdownBlock,
) : DefaultTask() {
    init {
        description = "Convert markdown files found in the project's resources path to source code in the final project"
    }

    private val markdownHandlers = markdownBlock.extensions.getByType<MarkdownHandlers>()
    private val markdownFeatures = markdownBlock.extensions.getByType<MarkdownFeatures>()

    private fun getMarkdownRoots(): Sequence<File> = project.getResourceRoots(project.jsTarget)
        .map { root -> root.resolve(markdownBlock.markdownPath.get()) }

    private fun getMarkdownFilesWithRoots(): List<RootAndFile> {
        val mdRoots = getMarkdownRoots()
        return project.getResourceFilesWithRoots(project.jsTarget)
            .filter { rootAndFile -> rootAndFile.file.extension == "md" }
            .mapNotNull { rootAndFile ->
                mdRoots.find { mdRoot -> rootAndFile.file.startsWith(mdRoot) }
                    ?.let { mdRoot -> RootAndFile(mdRoot, rootAndFile.file) }
            }
            .toList()
    }

    @InputFiles
    fun getMarkdownFiles(): List<File> {
        return getMarkdownFilesWithRoots().map { it.file }
    }

    @OutputDirectory
    fun getGenDir(): File = kobwebBlock.getGenJsSrcRoot<MarkdownBlock>(project).resolve(
        project.prefixQualifiedPackage(kobwebBlock.pagesPackage.get()).replace(".", "/")
    )

    @TaskAction
    fun execute() {
        val cache = NodeCache(markdownFeatures.createParser(), getMarkdownRoots().toList())
        getMarkdownFilesWithRoots().forEach { rootAndFile ->
            val mdFile = rootAndFile.file
            val mdPathRel = rootAndFile.relativeFile.invariantSeparatorsPath

            val parts = mdPathRel.split('/')
            val dirParts = parts.subList(0, parts.lastIndex)
            val packageParts = dirParts.map { it.toPackageName() }

            for (i in dirParts.indices) {
                if (dirParts[i] != packageParts[i]) {
                    // If not a match, that means the path that the markdown file is coming from is not compatible with
                    // Java package names, e.g. "2021" was converted to "_2021". This is fine -- we just need to tell
                    // Kobweb about the mapping.

                    val subpackage = packageParts.subList(0, i + 1)

                    File(getGenDir(), "${subpackage.joinToString("/")}/PackageMapping.kt")
                        // Multiple markdown files in the same folder will try to write this over and over again; we
                        // can skip after the first time
                        .takeIf { !it.exists() }
                        ?.let { mappingFile ->
                            mappingFile.parentFile.mkdirs()
                            mappingFile.writeText(
                                """
                                @file:PackageMapping("${dirParts[i]}")

                                package ${
                                    project.prefixQualifiedPackage(
                                        kobwebBlock.pagesPackage.get().packageConcat(
                                            subpackage.joinToString(".")
                                        )
                                    )
                                }

                                import com.varabyte.kobweb.core.PackageMapping
                            """.trimIndent()
                            )
                        }
                }
            }

            val ktFileName = mdFile.nameWithoutExtension
            File(getGenDir(), "${packageParts.joinToString("/")}/$ktFileName.kt").let { outputFile ->
                outputFile.parentFile.mkdirs()
                val mdPackage = project.prefixQualifiedPackage(
                    kobwebBlock.pagesPackage.get().packageConcat(packageParts.joinToString("."))
                )

                // The suggested replacement for "capitalize" is awful
                @Suppress("DEPRECATION")
                val funName = "${ktFileName.capitalize()}Page"
                val ktRenderer = KotlinRenderer(
                    project,
                    cache::getRelative,
                    markdownBlock.imports.get(),
                    mdPathRel,
                    markdownHandlers,
                    mdPackage,
                    markdownBlock.routeOverride.orNull,
                    funName,
                    LoggingReporter(logger),
                )
                outputFile.writeText(ktRenderer.render(cache[mdFile]))
            }
        }
    }

    /**
     * Class which maintains a cache of parsed markdown content associated with their source files.
     *
     * This cache is useful because Markdown files can reference other Markdown files, meaning as we process a
     * collection of them, we might end up referencing the same file multiple times.
     *
     * Note that this cache should not be created with too long a lifetime, because users may edit Markdown files and
     * those changes should be picked up. It is intended to be used only for a single processing run across a collection
     * of markdown files and then discarded.
     *
     * @param parser The parser to use to parse markdown files.
     * @param roots A collection of root folders under which Markdown files should be considered for processing. Any
     *   markdown files referenced outside of these roots should be ignored for caching purposes.
     */
    private class NodeCache(private val parser: Parser, private val roots: List<File>) {
        private val existingNodes = mutableMapOf<String, Node>()

        /**
         * Returns a parsed Markdown [Node] for the target file (which is expected to be a valid markdown file).
         *
         * Once queried, the node will be cached so that subsequent calls to this method will not re-read the file. If
         * the file fails to parse, this method will throw an exception.
         */
        operator fun get(file: File): Node = file.canonicalFile.let { canonicalFile ->
            require(roots.any { canonicalFile.startsWith(it) }) {
                "File $canonicalFile is not under any of the specified Markdown roots: $roots"
            }
            existingNodes.computeIfAbsent(canonicalFile.invariantSeparatorsPath) {
                parser.parse(canonicalFile.readText())
            }
        }

        /**
         * Returns a parsed Markdown node given a relative path which will be resolved against all markdown roots.
         *
         * For example, "test/example.md" will return parsed markdown information if found in
         * `src/jsMain/resources/markdown/test/example.md`.
         *
         * This will return null if:
         * * no file is found matching the passed in path.
         * * the file at the specified location fails to parse.
         * * the relative file path escapes the current root, e.g. `../public/files/license.md`, as this could be a
         *   useful way to link to a raw markdown file that should be served as is and not converted into an html page.
         */
        fun getRelative(relPath: String): Node? = try {
            roots.asSequence()
                .map { it to it.resolve(relPath).canonicalFile }
                // Make sure we don't access anything outside our markdown roots
                .firstOrNull { (root, canonicalFile) -> canonicalFile.exists() && canonicalFile.isFile && canonicalFile.startsWith(root) }
                ?.second?.let(::get)
        } catch (ignored: IOException) {
            null
        }
    }
}
