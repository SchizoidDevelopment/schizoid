package dev.lyzev.api.opengl

import dev.lyzev.schizoid.Schizoid
import dev.lyzev.schizoid.feature.features.module.modules.render.ModuleToggleableRearView
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.renderer.GameRenderer
import net.minecraft.client.renderer.ItemInHandRenderer
import net.minecraft.client.renderer.LevelRenderer
import net.minecraft.client.renderer.extract.LevelExtractor
import net.minecraft.core.BlockPos
import net.minecraft.server.packs.resources.ResourceManager

/** Experimental second world pass. All methods are called on the Minecraft render thread. */
object RearViewPipeline {
    private val constructing = ThreadLocal.withInitial { false }
    private var game: GameRenderer? = null
    private var renderer: LevelRenderer? = null
    private var extractor: LevelExtractor? = null
    private var level: ClientLevel? = null
    private var prepared = false
    private var drawing = false
    private var extracting = false
    private var sodiumTerrainPrepared = false
    private var hasRendered = false
    private var failed = false
    private var width = 0
    private var height = 0
    private var needsChunkSeed = true


    @JvmStatic fun isConstructing() = constructing.get()
    @JvmStatic fun isRearRenderer(value: GameRenderer) = value === game
    @JvmStatic fun isRearExtractor(value: LevelExtractor) = value === extractor
    @JvmStatic fun secondaryGame(): GameRenderer = checkNotNull(game)
    @JvmStatic fun isDrawing() = drawing
    @JvmStatic fun isExtracting() = extracting
    @JvmStatic fun markSodiumTerrainPrepared() { if (extracting) sodiumTerrainPrepared = true }
    @JvmStatic fun extractorFor(value: GameRenderer, original: LevelExtractor): LevelExtractor = if (value === game) checkNotNull(extractor) else original
    @JvmStatic fun rendererFor(value: GameRenderer, original: LevelRenderer): LevelRenderer = if (value === game) checkNotNull(renderer) else original
    @JvmStatic fun gameRendererFor(value: LevelExtractor, original: GameRenderer): GameRenderer = if (value === extractor) checkNotNull(game) else original

    private val sodiumPresent = net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("sodium")
    @JvmStatic fun sodiumProjection(): org.joml.Matrix4fc {
        val storage = Class.forName("net.caffeinemc.mods.sodium.client.util.GameRendererStorage")
        return storage.getMethod("sodium\$getProjectionMatrix").invoke(checkNotNull(game)) as org.joml.Matrix4fc
    }
    private fun sodiumWorldRenderer(value: LevelRenderer): Any {
        val extension = Class.forName("net.caffeinemc.mods.sodium.client.world.LevelRendererExtension")
        return extension.getMethod("sodium\$getWorldRenderer").invoke(value)
    }
    /** A new occlusion graph has never seen the cache's earlier load/empty deltas. */
    @JvmStatic fun seedLoadedChunks(value: LevelExtractor) {
        if (sodiumPresent || value !== extractor || !needsChunkSeed) return
        val currentLevel = level ?: return
        val currentGame = game ?: return
        val currentRenderer = renderer ?: return
        val chunks = it.unimi.dsi.fastutil.longs.LongOpenHashSet()
        val empty = it.unimi.dsi.fastutil.longs.LongOpenHashSet()
        val pos = currentGame.mainCamera().position()
        val cx = net.minecraft.core.SectionPos.blockToSectionCoord(net.minecraft.util.Mth.floor(pos.x))
        val cz = net.minecraft.core.SectionPos.blockToSectionCoord(net.minecraft.util.Mth.floor(pos.z))
        val radius = Minecraft.getInstance().options.getEffectiveRenderDistance() + 3
        for (x in cx-radius..cx+radius) for (z in cz-radius..cz+radius) {
            val chunk = currentLevel.chunkSource.getChunk(x, z, net.minecraft.world.level.chunk.status.ChunkStatus.FULL, false) ?: continue
            chunks.add(net.minecraft.world.level.ChunkPos.pack(x, z))
            for (index in chunk.sections.indices) if (chunk.sections[index].hasOnlyAir()) {
                empty.add(net.minecraft.core.SectionPos.asLong(x, currentLevel.getSectionYFromSectionIndex(index), z))
            }
        }
        currentRenderer.sectionOcclusionGraph().updateLoadedChunks(chunks, it.unimi.dsi.fastutil.longs.LongOpenHashSet())
        currentRenderer.sectionOcclusionGraph().updateEmptySections(empty, it.unimi.dsi.fastutil.longs.LongOpenHashSet())
        currentRenderer.sectionOcclusionGraph().invalidate()
        needsChunkSeed = false
    }
    @JvmStatic fun bindSodiumExtractor(value: LevelExtractor) {
        if (!sodiumPresent || value !== extractor) return
        val field = LevelExtractor::class.java.getDeclaredField("renderer")
        field.isAccessible = true
        field.set(value, sodiumWorldRenderer(checkNotNull(renderer)))
    }
    private fun sodiumSetLevel(value: LevelRenderer, newLevel: ClientLevel?) {
        if (!sodiumPresent) return
        // No hard class linkage: Sodium is an optional dependency.
        val sodium = sodiumWorldRenderer(value)
        sodium.javaClass.getMethod("setLevel", ClientLevel::class.java).invoke(sodium, newLevel)
    }

    /**
     * Runs [block] with the secondary renderer, level renderer and extractor installed as
     * Minecraft's own. Vanilla resolves output targets, lightmap, clouds, particles and many
     * other pieces through those fields, so without this the rear pass draws terrain and
     * entities into the primary frame and leaves only the sky in its own target.
     */
    private inline fun <T> asGlobals(mc: Minecraft, block: () -> T): T {
        val access = mc as dev.lyzev.schizoid.injection.accessor.RearMinecraftAccessor
        val primaryGame = mc.gameRenderer
        val primaryRenderer = mc.levelRenderer
        val primaryExtractor = mc.levelExtractor
        check(primaryGame !== game) { "Rear view pass is already active" }
        access.`schizoid$setGameRenderer`(checkNotNull(game))
        access.`schizoid$setLevelRenderer`(checkNotNull(renderer))
        access.`schizoid$setLevelExtractor`(checkNotNull(extractor))
        try {
            return block()
        } finally {
            access.`schizoid$setGameRenderer`(primaryGame)
            access.`schizoid$setLevelRenderer`(primaryRenderer)
            access.`schizoid$setLevelExtractor`(primaryExtractor)
        }
    }

    /**
     * The level clear leaves alpha 0 and translucent passes write partial alpha. ImGui blends
     * the texture, so make the finished frame opaque (the 1.21 module used a no-alpha pass).
     */
    private fun makeOpaque(target: com.mojang.blaze3d.pipeline.RenderTarget) {
        Render.isolated {
            OpenGlBridge.bindTarget(target, true)
            org.lwjgl.opengl.GL33C.glDisable(org.lwjgl.opengl.GL33C.GL_SCISSOR_TEST)
            org.lwjgl.opengl.GL33C.glColorMask(false, false, false, true)
            org.lwjgl.opengl.GL33C.glClearColor(0f, 0f, 0f, 1f)
            org.lwjgl.opengl.GL33C.glClear(org.lwjgl.opengl.GL33C.GL_COLOR_BUFFER_BIT)
        }
    }

    /** Section counters of the secondary renderer, for diagnostics. */
    @JvmStatic fun debugStats(): String {
        val r = renderer ?: return "inactive"
        val e = extractor ?: return "inactive"
        return "visible=${r.visibleSections().size} rendered=${e.countRenderedSections()} " +
            "dispatcher=${r.sectionRenderDispatcher()?.stats} prepared=$prepared failed=$failed hasRendered=$hasRendered"
    }

    private fun ensure(mc: Minecraft) {
        if (game != null) return
        constructing.set(true)
        val g = try { GameRenderer(mc, ItemInHandRenderer(mc, mc.entityRenderDispatcher, mc.itemModelResolver), mc.modelManager) } finally { constructing.set(false) }
        game = g
        try {
            width = mc.window.width.coerceAtLeast(1)
            height = mc.window.height.coerceAtLeast(1)
            val r = LevelRenderer(mc.entityRenderDispatcher, mc.blockEntityRenderDispatcher, mc.modelManager, mc.textureManager, mc.atlasManager, mc.shaderManager, g, width, height)
            renderer = r
            extractor = LevelExtractor(mc, g.gameRenderState().levelRenderState, r)
            g.setRenderBlockOutline(false)
        } catch (t: Throwable) {
            val partial = renderer
            extractor = null
            renderer = null
            game = null
            try { partial?.close() } catch (cleanup: Throwable) { if (cleanup !== t) t.addSuppressed(cleanup) }
            try { g.close() } catch (cleanup: Throwable) { if (cleanup !== t) t.addSuppressed(cleanup) }
            throw t
        }
    }

    @JvmStatic fun extract(mc: Minecraft, delta: DeltaTracker, renderLevel: Boolean) {
        prepared = false
        val player = mc.player ?: return
        if (!renderLevel || mc.level == null || !ModuleToggleableRearView.isEnabled || ModuleToggleableRearView.hide || failed) return
        val cameraEntity = mc.cameraEntity ?: player
        try {
            ensure(mc)
            val g = checkNotNull(game)
            val r = checkNotNull(renderer)
            val e = checkNotNull(extractor)
            val currentLevel = checkNotNull(mc.level)
            asGlobals(mc) {
            if (level !== currentLevel) {
                level = currentLevel
                g.setLevel(currentLevel)
                g.mainCamera().setEntity(cameraEntity)
                // Sodium's setLevel return hook hard-codes Minecraft.levelRenderer;
                // avoid invoking it on this secondary extractor. Initialise its vanilla
                // fields directly and initialise Sodium's own renderer separately.
                if (sodiumPresent) {
                    sodiumSetLevel(r, currentLevel)
                    (e as dev.lyzev.schizoid.injection.accessor.RearExtractorLevelAccessor).apply {
                        `schizoid$setRearLevel`(currentLevel)
                        `schizoid$setResetLevelRenderData`(true)
                    }
                    e.allChanged()
                } else e.setLevel(currentLevel)
            }
            val w = mc.window.width.coerceAtLeast(1)
            val h = mc.window.height.coerceAtLeast(1)
            if (w != width || h != height) { width = w; height = h; g.mainRenderTarget().resize(w,h); r.resize(w,h) }
            g.mainCamera().setEntity(cameraEntity)
            g.update(delta)
            // Fabric normally prepares only Minecraft's singleton extractor. Populate the
            // secondary context with its actual renderer, state and camera before Fabric events.
            (e as dev.lyzev.schizoid.injection.accessor.RearFabricExtractorAccessor)
                .`schizoid$getFabricExtractionContext`().prepare(g, r, g.gameRenderState().levelRenderState, currentLevel, delta, g.mainCamera())
            sodiumTerrainPrepared = false
            extracting = true
            try { g.extract(delta, true) } finally { extracting = false }
            // Sodium injects setupTerrain into a branch skipped when vanilla rebuilds
            // geometry (first frame/allChanged). Never draw stale terrain uniforms.
            prepared = !sodiumPresent || sodiumTerrainPrepared
            if (!prepared) hasRendered = false
            }
        } catch (t: Throwable) {
            // LevelExtractor mutates profiler and shared dispatchers; an exception may
            // leave them inconsistent, so do not continue into primary extraction.
            failed = true
            Schizoid.logger.error("Rear view extraction failed; aborting this client render", t)
            throw t
        }
    }

    @JvmStatic fun tick() {
        // GameRenderer.tick also advances its own camera; the secondary hand renderer
        // is separately constructed and does not mutate the primary renderer's hand state.
        if (Minecraft.getInstance().player != null && Minecraft.getInstance().level != null) game?.tick()
    }

    @JvmStatic fun render(delta: DeltaTracker) {
        if (!prepared || failed) return
        prepared = false
        drawing = true
        try {
            val g = checkNotNull(game)
            Render.isolated { asGlobals(Minecraft.getInstance()) { g.render(delta, true) } }
            makeOpaque(g.mainRenderTarget())
            hasRendered = true
        } catch (t: Throwable) {
            // Vanilla rendering can leave profiler, matrix or framegraph state partially
            // mutated on exception. Continuing the primary pass would hide corruption.
            failed = true
            Schizoid.logger.error("Rear view rendering failed; aborting this client render", t)
            throw t
        } finally {
            drawing = false
            try { renderer?.endFrame() } catch (t: Throwable) {
                failed = true
                Schizoid.logger.error("Rear view frame cleanup failed", t)
            }
        }
    }

    /** Only valid on render thread while the active level and secondary target remain alive. */
    @JvmStatic fun target(): com.mojang.blaze3d.pipeline.RenderTarget? = if (!failed && hasRendered && level != null) game?.mainRenderTarget() else null
    @JvmStatic fun texture(): Int? = target()?.let(OpenGlBridge::colorTextureId)
    @JvmStatic fun close() {
        needsChunkSeed = true
        prepared = false
        hasRendered = false
        level = null
        val oldRenderer = renderer
        val oldGame = game
        extractor = null
        renderer = null
        game = null
        failed = false
        var failure: Throwable? = null
        fun release(action: () -> Unit) {
            try { action() } catch (t: Throwable) {
                if (failure == null) failure = t else if (failure !== t) failure!!.addSuppressed(t)
            }
        }
        // Discard secondary extractor without setLevel(null): vanilla resets the
        // shared dispatcher, and Sodium's hook would unload the primary renderer.
        if (oldRenderer != null) release { sodiumSetLevel(oldRenderer, null) }
        if (oldRenderer != null) release { oldRenderer.close() }
        if (oldGame != null) release { OpenGlBridge.releaseTarget(oldGame.mainRenderTarget()) }
        if (oldGame != null) release { oldGame.close() }
        failure?.let { Schizoid.logger.error("Rear view cleanup failed", it) }
    }
    private fun shouldForward(source: LevelExtractor) = extractor != null && source !== extractor
    private fun sodiumCall(name: String, types: Array<out Class<*>>, vararg args: Any) {
        val target = sodiumWorldRenderer(checkNotNull(renderer))
        target.javaClass.getMethod(name, *types).invoke(target, *args)
    }
    private val int3Bool = arrayOf(Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!)
    private val int6Bool = arrayOf(Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!)
    @JvmStatic fun forwardAllChanged(source: LevelExtractor) { if (shouldForward(source)) { needsChunkSeed = true; extractor?.allChanged() } }
    @JvmStatic fun forwardSampler(source: LevelExtractor) { if (shouldForward(source)) extractor?.resetSampler() }
    @JvmStatic fun forwardSection(source: LevelExtractor, x:Int,y:Int,z:Int) { if (shouldForward(source)) { if (sodiumPresent) sodiumCall("scheduleRebuildForChunk", int3Bool, x,y,z,false) else extractor?.setSectionDirty(x,y,z) } }
    @JvmStatic fun forwardNeighbors(source: LevelExtractor, x:Int,y:Int,z:Int) { if (shouldForward(source)) { if (sodiumPresent) sodiumCall("scheduleRebuildForChunks", int6Bool, x-1,y-1,z-1,x+1,y+1,z+1,false) else extractor?.setSectionDirtyWithNeighbors(x,y,z) } }
    @JvmStatic fun forwardRange(source: LevelExtractor, x1:Int,y1:Int,z1:Int,x2:Int,y2:Int,z2:Int) { if (shouldForward(source)) { if (sodiumPresent) sodiumCall("scheduleRebuildForChunks", int6Bool, x1,y1,z1,x2,y2,z2,false) else extractor?.setSectionRangeDirty(x1,y1,z1,x2,y2,z2) } }
    @JvmStatic fun forwardBlock(source: LevelExtractor, pos:BlockPos, flags:Int) { if (shouldForward(source)) { if (sodiumPresent) sodiumCall("scheduleRebuildForBlockArea", int6Bool, pos.x-1,pos.y-1,pos.z-1,pos.x+1,pos.y+1,pos.z+1,(flags and 8) != 0) else extractor?.blockChanged(pos,flags) } }
    @JvmStatic fun forwardBlockState(source: LevelExtractor, pos:BlockPos, oldState:net.minecraft.world.level.block.state.BlockState, newState:net.minecraft.world.level.block.state.BlockState) { if (shouldForward(source)) { if (sodiumPresent) sodiumCall("scheduleRebuildForBlockArea", int6Bool, pos.x,pos.y,pos.z,pos.x,pos.y,pos.z,false) else extractor?.setBlockDirty(pos, oldState, newState) } }
    @JvmStatic fun forwardBlocks(source: LevelExtractor, x1:Int,y1:Int,z1:Int,x2:Int,y2:Int,z2:Int) { if (shouldForward(source)) { if (sodiumPresent) sodiumCall("scheduleRebuildForBlockArea", int6Bool, x1,y1,z1,x2,y2,z2,false) else extractor?.setBlocksDirty(x1,y1,z1,x2,y2,z2) } }
    @JvmStatic fun forwardReload(source: LevelExtractor, manager:ResourceManager) { if (shouldForward(source)) extractor?.onResourceManagerReload(manager) }
}
