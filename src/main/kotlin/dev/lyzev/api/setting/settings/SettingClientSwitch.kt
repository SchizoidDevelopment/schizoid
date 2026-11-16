/*
 * Copyright (c) 2023. Schizoid
 * All rights reserved.
 */

package dev.lyzev.api.setting.settings

import dev.lyzev.api.animation.EasingFunction
import dev.lyzev.api.animation.TimeAnimator
import dev.lyzev.api.setting.SettingClient
import dev.lyzev.schizoid.feature.IFeature
import imgui.ImColor
import imgui.ImGui.*
import imgui.flag.ImGuiCol.Button
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import net.minecraft.util.Mth
import kotlin.reflect.KClass
import kotlin.reflect.KProperty

/**
 * A specific implementation of the [SettingClient] class for switch settings.
 *
 * @param container The class of the settings container where this setting belongs.
 * @param name The name of the setting.
 * @param value The initial value of the boolean setting.
 * @param hide A lambda function that determines whether this setting is hidden or not.
 * @param change A lambda function that will be called when the value of the setting changes.
 */
class SettingClientSwitch(
    container: KClass<out IFeature>,
    name: String,
    desc: String?,
    value: Boolean,
    hide: () -> Boolean,
    change: (Boolean) -> Unit
) : SettingClient<Boolean>(container, name, desc, value, hide, change) {

    private val switch = Switch()

    private var shadowValue
        get() = value
        set(value) {
            this.value = value
            switch.timeAnimator.setReversed(!value)
        }

    override fun render() {
        text(name)
        if (desc != null && isItemHovered()) setTooltip(desc)
        sameLine()
        v[0] = shadowValue
        switch.render(name, v)
        if (v[0] != shadowValue)
            shadowValue = v[0]
    }

    override fun load(value: JsonElement) {
        this.shadowValue = value.jsonPrimitive.boolean
    }

    override fun save(): JsonElement = JsonPrimitive(shadowValue)

    override fun setValue(ref: Any, prop: KProperty<*>, value: Boolean) {
        switch.timeAnimator.setReversed(!value)
        super.setValue(ref, prop, value)
    }

    override fun reset() {
        super.reset()
        switch.timeAnimator.setReversed(!value)
    }

    init {
        switch.timeAnimator.setReversed(!value)
    }

    companion object {
        private val v = booleanArrayOf(false)
    }
}

class Switch {

    val timeAnimator = TimeAnimator(300)

    fun render(strId: String, v: BooleanArray) {
        val p = getCursorScreenPos()
        val drawList = getWindowDrawList()
        val height = 17.5f
        val width = height * 1.8f
        val radius = height * 0.5f
        if (invisibleButton(strId, width, height))
            v[0] = !v[0]
        val delta = EasingFunction.LINEAR.ease(timeAnimator.getProgress()).toFloat()
        val primary = getStyle().getColor(Button)
        val hsvTrackColor = ImColor.rgb(
            Mth.lerp(delta, track[0], primary.x),
            Mth.lerp(delta, track[1], primary.y),
            Mth.lerp(delta, track[2], primary.z)
        )
        drawList.addRectFilled(
            p.x,
            p.y + height * 0.15f,
            p.x + width,
            p.y + height * 0.85f,
            hsvTrackColor,
            height * 0.35f
        )
        drawList.addCircleFilled(
            (p.x + radius + (width - height) * EasingFunction.IN_OUT_BACK.ease(timeAnimator.getProgress())).toFloat(),
            p.y + height * 0.5f,
            radius,
            thumb
        )
    }

    companion object {
        private val track = floatArrayOf(.8f, .8f, .8f)
        private var thumb = ImColor.rgb(255, 255, 255)
    }
}

fun IFeature.switch(
    name: String,
    desc: String? = null,
    value: Boolean,
    hide: () -> Boolean = { false },
    change: (Boolean) -> Unit = {}
) = SettingClientSwitch(this::class, name, desc, value, hide, change)
