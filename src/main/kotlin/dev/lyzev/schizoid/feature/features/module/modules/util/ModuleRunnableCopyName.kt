/*
 * Copyright (c) 2024. Schizoid
 * All rights reserved.
 */

package dev.lyzev.schizoid.feature.features.module.modules.util

import dev.lyzev.schizoid.feature.IFeature
import dev.lyzev.schizoid.feature.features.module.ModuleRunnable

object ModuleRunnableCopyName :
    ModuleRunnable("Copy Name", "Copies your username to the clipboard.", category = IFeature.Category.UTIL) {

    override fun invoke(): String? {
        copy(mc.user.name)
        return null
    }
}
