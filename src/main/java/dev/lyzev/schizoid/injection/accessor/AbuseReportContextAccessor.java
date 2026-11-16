/*
 * Copyright (c) 2024. Schizoid
 * All rights reserved.
 */

package dev.lyzev.schizoid.injection.accessor;

import net.minecraft.client.multiplayer.chat.report.ReportEnvironment;
import net.minecraft.client.multiplayer.chat.report.ReportingContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Provides the means to access protected members of the Abuse Report Context.
 */
@Mixin(ReportingContext.class)
public interface AbuseReportContextAccessor {
    /**
     * Returns the reporter environment.
     *
     * @return environment
     */
    @Accessor("environment")
    ReportEnvironment getEnvironment();
}
