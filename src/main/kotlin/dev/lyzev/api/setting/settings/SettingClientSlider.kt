/*
 * Copyright (c) 2024. Schizoid
 * All rights reserved.
 */

package dev.lyzev.api.setting.settings

import dev.lyzev.api.setting.SettingClient
import dev.lyzev.schizoid.feature.IFeature
import imgui.ImGui.*
import kotlinx.serialization.json.*
import net.minecraft.util.Mth
import kotlin.reflect.KClass
import kotlin.reflect.KProperty

/**
 * A specific implementation of the [SettingClient] class for slider settings.
 *
 * @param container The class of the settings container where this setting belongs.
 * @param name The name of the setting.
 * @param desc The description of the setting.
 * @param value The initial value of the slider setting.
 * @param minValue The minimum value of the slider setting.
 * @param maxValue The maximum value of the slider setting.
 * @param decimalPlaces The number of decimal places for the slider setting (only applicable for Float type).
 * @param unit The unit of the slider setting.
 * @param allowOutOfBounds A flag to determine whether the slider setting allows out of bounds values.
 * @param hide A lambda function that determines whether this setting is hidden or not.
 * @param onChange A lambda function that will be called when the value of the setting changes.
 */
@Suppress("UNCHECKED_CAST")
class SettingClientSlider<T : Number>(
    container: KClass<out IFeature>,
    name: String,
    desc: String?,
    value: T,
    private val minValue: T,
    private val maxValue: T,
    private val decimalPlaces: Int,
    private val unit: String?,
    private val allowOutOfBounds: Boolean,
    private val onlyUpdateOnRelease: Boolean,
    hide: () -> Boolean,
    onChange: (T) -> Unit
) : SettingClient<T>(container, name, desc, value, hide, onChange) {

    override fun render() {
        text(name)
        if (desc != null && isItemHovered()) setTooltip(desc)
        if (value is Int) {
            setNextItemWidth(getColumnWidth())
            if (sliderInt("", i, minValue as Int, maxValue as Int, "%d" + if (unit != null) " $unit" else "")) {
                if (!onlyUpdateOnRelease) {
                    value = if (allowOutOfBounds)
                        i[0] as T
                    else
                        Mth.clamp(i[0], minValue.toInt(), maxValue.toInt()) as T
                }
            }
            if (isItemDeactivatedAfterEdit() && onlyUpdateOnRelease) {
                value = if (allowOutOfBounds)
                    i[0] as T
                else
                    Mth.clamp(i[0], minValue.toInt(), maxValue.toInt()) as T
            }
        } else if (value is Float) {
            setNextItemWidth(getColumnWidth())
            if (sliderFloat(
                    "",
                    f,
                    minValue as Float,
                    maxValue as Float,
                    "%.${decimalPlaces}f" + if (unit != null) " $unit" else ""
                )
            ) {
                if (!onlyUpdateOnRelease) {
                    value = if (allowOutOfBounds)
                        f[0] as T
                    else
                        Mth.clamp(f[0], minValue.toFloat(), maxValue.toFloat()) as T
                }
            }
            if (isItemDeactivatedAfterEdit() && onlyUpdateOnRelease) {
                value = if (allowOutOfBounds)
                    f[0] as T
                else
                    Mth.clamp(f[0], minValue.toFloat(), maxValue.toFloat()) as T
            }
        }
        if (isItemHovered()) setTooltip((if (allowOutOfBounds) "Out of bounds values are allowed, use with caution." else "Range: $minValue ${unit ?: ""} - $maxValue ${unit ?: ""}") + "\nPress CTRL + click to set a specific value.")
    }

    override fun setValue(ref: Any, prop: KProperty<*>, value: T) {
        if (allowOutOfBounds)
            super.setValue(ref, prop, value)
        else if (this.value is Int)
            super.setValue(ref, prop, Mth.clamp(value.toInt(), minValue.toInt(), maxValue.toInt()) as T)
        else if (this.value is Float)
            super.setValue(ref, prop, Mth.clamp(value.toFloat(), minValue.toFloat(), maxValue.toFloat()) as T)
        if (this.value is Int)
            i[0] = this.value as Int
        else if (this.value is Float)
            f[0] = this.value as Float
    }

    override fun load(value: JsonElement) {
        this.value = when (this.value) {
            is Int -> (value.jsonPrimitive.int.let {
                if (allowOutOfBounds) it else it.coerceIn(
                    minValue.toInt(),
                    maxValue.toInt()
                )
            } as T).apply { i[0] = this as Int }

            is Float -> (value.jsonPrimitive.float.let {
                if (allowOutOfBounds) it else it.coerceIn(
                    minValue.toFloat(),
                    maxValue.toFloat()
                )
            } as T).apply { f[0] = this as Float }

            else -> return
        }
    }

    override fun save(): JsonElement = JsonPrimitive(value)

    /**
     * An array to store the integer value of the slider.
     */
    private val i = intArrayOf(0).apply { if (value is Int) this[0] = value as Int }

    /**
     * An array to store the float value of the slider.
     */
    private val f = floatArrayOf(0f).apply { if (value is Float) this[0] = value as Float }
}

/**
 * Creates a new integer slider setting.
 *
 * @param name The name of the setting.
 * @param desc The description of the setting.
 * @param value The initial value of the slider setting.
 * @param minValue The minimum value of the slider setting.
 * @param maxValue The maximum value of the slider setting.
 * @param unit The unit of the slider setting.
 * @param allowOutOfBounds A flag to determine whether the slider setting allows out of bounds values.
 * @param onlyUpdateOnRelease A flag to determine whether the slider setting only updates on release.
 * @param hide A lambda function that determines whether this setting is hidden or not.
 * @param onChange A lambda function that will be called when the value of the setting changes.
 *
 * @return The created integer slider setting.
 */
fun IFeature.slider(
    name: String,
    desc: String? = null,
    value: Int,
    minValue: Int,
    maxValue: Int,
    unit: String? = null,
    allowOutOfBounds: Boolean = false,
    onlyUpdateOnRelease: Boolean = false,
    hide: () -> Boolean = { false },
    onChange: (Int) -> Unit = {}
) = SettingClientSlider(
    this::class,
    name,
    desc,
    value,
    minValue,
    maxValue,
    0,
    unit,
    allowOutOfBounds,
    onlyUpdateOnRelease,
    hide,
    onChange
)

/**
 * Creates a new float slider setting.
 *
 * @param name The name of the setting.
 * @param desc The description of the setting.
 * @param value The initial value of the slider setting.
 * @param minValue The minimum value of the slider setting.
 * @param maxValue The maximum value of the slider setting.
 * @param decimalPlaces The number of decimal places for the slider setting.
 * @param unit The unit of the slider setting.
 * @param allowOutOfBounds A flag to determine whether the slider setting allows out of bounds values.
 * @param onlyUpdateOnRelease A flag to determine whether the slider setting only updates on release.
 * @param hide A lambda function that determines whether this setting is hidden or not.
 * @param onChange A lambda function that will be called when the value of the setting changes.
 *
 * @return The created float slider setting.
 */
fun IFeature.slider(
    name: String,
    desc: String? = null,
    value: Float,
    minValue: Float,
    maxValue: Float,
    decimalPlaces: Int = 1,
    unit: String? = null,
    allowOutOfBounds: Boolean = false,
    onlyUpdateOnRelease: Boolean = false,
    hide: () -> Boolean = { false },
    onChange: (Float) -> Unit = {}
) = SettingClientSlider(
    this::class,
    name,
    desc,
    value,
    minValue,
    maxValue,
    decimalPlaces,
    unit,
    allowOutOfBounds,
    onlyUpdateOnRelease,
    hide,
    onChange
)
