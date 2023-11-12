/*
 * Copyright (c) 2023. Schizoid
 * All rights reserved.
 */

package dev.lyzev.schizoid.feature.features.command.argument.arguments

import dev.lyzev.schizoid.feature.features.command.argument.Argument

/**
 * A string argument.
 *
 * @property name The name of the argument.
 * @property desc The description of the argument.
 * @property isRequired Whether the argument is required.
 * @property value The value of the argument.
 * @param T The type of the argument.
 */
class ArgumentString(name: String, desc: String? = null, isRequired: Boolean = true) :
    Argument<String>(name, desc, isRequired) {

    override fun parse(input: String): Pair<Boolean, String> {
        value = input
        return value!!.isNotBlank() to "Invalid string: $input"
    }
}
