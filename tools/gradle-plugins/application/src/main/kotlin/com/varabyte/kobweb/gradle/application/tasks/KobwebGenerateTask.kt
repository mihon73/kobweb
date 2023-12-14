package com.varabyte.kobweb.gradle.application.tasks

import com.varabyte.kobweb.gradle.core.extensions.KobwebBlock
import com.varabyte.kobweb.gradle.core.tasks.KobwebModuleTask

/**
 * Common base class for all "Kobweb generate" tasks.
 *
 * This allows us to search for all Kobweb generate tasks by type.
 */
abstract class KobwebGenerateTask(kobwebBlock: KobwebBlock, desc: String) : KobwebModuleTask(kobwebBlock, desc)
