package com.varabyte.kobweb.gradle.library

import com.varabyte.kobweb.ProcessorMode
import com.varabyte.kobweb.gradle.core.KobwebCorePlugin
import com.varabyte.kobweb.gradle.core.extensions.kobwebBlock
import com.varabyte.kobweb.gradle.core.kmp.JsTarget
import com.varabyte.kobweb.gradle.core.kmp.JvmTarget
import com.varabyte.kobweb.gradle.core.kmp.buildTargets
import com.varabyte.kobweb.gradle.core.kmp.jsTarget
import com.varabyte.kobweb.gradle.core.kmp.jvmTarget
import com.varabyte.kobweb.gradle.core.kmp.kotlin
import com.varabyte.kobweb.gradle.core.ksp.applyKspPlugin
import com.varabyte.kobweb.gradle.core.ksp.setupKspJs
import com.varabyte.kobweb.gradle.core.ksp.setupKspJvm
import com.varabyte.kobweb.gradle.core.metadata.LibraryIndexMetadata
import com.varabyte.kobweb.gradle.core.metadata.ModuleMetadata
import com.varabyte.kobweb.gradle.core.util.KobwebVersionUtil
import com.varabyte.kobweb.gradle.library.extensions.createLibraryBlock
import com.varabyte.kobweb.gradle.library.extensions.index
import com.varabyte.kobweb.ksp.KOBWEB_METADATA_INDEX
import com.varabyte.kobweb.ksp.KOBWEB_METADATA_MODULE
import kotlinx.html.head
import kotlinx.html.stream.createHTML
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.withType
import org.gradle.language.jvm.tasks.ProcessResources
import org.jetbrains.kotlin.gradle.targets.js.ir.KotlinJsIrTarget
import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget

@Suppress("unused") // KobwebApplicationPlugin is found by Gradle via reflection
class KobwebLibraryPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.pluginManager.apply(KobwebCorePlugin::class.java)
        val libraryBlock = project.kobwebBlock.createLibraryBlock()
        project.applyKspPlugin()

        val createModuleMetadataTask = project.tasks.register("kobwebGenerateModuleMetadata") {
            val kobwebVersion = KobwebVersionUtil.version
            inputs.property("kobwebVersion", kobwebVersion)

            val generatedResourcesDir = project.layout.buildDirectory.dir("generated/kobweb/module")

            val moduleMetadataFile = generatedResourcesDir.get().file(KOBWEB_METADATA_MODULE)
            outputs.dir(generatedResourcesDir)

            doLast {
                moduleMetadataFile.asFile.apply {
                    parentFile.mkdirs()
                    writeText(Json.encodeToString(ModuleMetadata(kobwebVersion)))
                }
            }
        }

        val createIndexMetadataTask = project.tasks.register("kobwebCreateIndexMetadata") {
            val generatedResourcesDir = project.layout.buildDirectory.dir("generated/kobweb/index")
            val indexMetadataFile = generatedResourcesDir.get().file(KOBWEB_METADATA_INDEX)
            outputs.dir(generatedResourcesDir)

            doLast {
                val headElements = libraryBlock.index.head.orNull?.takeIf { it.isNotEmpty() } ?: return@doLast

                indexMetadataFile.asFile.apply {
                    parentFile.mkdirs()
                    writeText(
                        Json.encodeToString(
                            LibraryIndexMetadata(
                                createHTML().head {
                                    headElements.forEach { element -> element() }
                                }
                            )
                        )
                    )
                }
            }
        }

        project.buildTargets.withType<KotlinJsIrTarget>().configureEach {
            val jsTarget = JsTarget(this)
            project.setupKspJs(jsTarget, ProcessorMode.LIBRARY)
            project.tasks.named<ProcessResources>(jsTarget.processResources) {
                from(createModuleMetadataTask)
            }
            project.kotlin.sourceSets.named(jsTarget.mainSourceSet) {
                resources.srcDir(createIndexMetadataTask)
            }
        }

        project.buildTargets.withType<KotlinJvmTarget>().configureEach {
            val jvmTarget = JvmTarget(this)
            project.setupKspJvm(jvmTarget)
            project.tasks.named<ProcessResources>(jvmTarget.processResources) {
                from(createModuleMetadataTask)
            }
        }
    }
}

@Deprecated(
    "Add the task outputs to the source set directly instead. Note that you may have to adjust the task to output a directory instead of a file.",
    ReplaceWith("kotlin.sourceSets.getByName(\"jsMain\").kotlin.srcDir(task)"),
)
fun Project.notifyKobwebAboutFrontendCodeGeneratingTask(task: Task) {
    tasks.matching { it.name == jsTarget.kspKotlin }.configureEach { dependsOn(task) }
}

@Deprecated(
    "Add the task outputs to the source set directly instead. Note that you may have to adjust the task to output a directory instead of a file.",
    ReplaceWith("kotlin.sourceSets.getByName(\"jvmMain\").kotlin.srcDir(task)"),
)
fun Project.notifyKobwebAboutBackendCodeGeneratingTask(task: Task) {
    tasks.matching { it.name == jvmTarget?.kspKotlin }.configureEach { dependsOn(task) }
}
