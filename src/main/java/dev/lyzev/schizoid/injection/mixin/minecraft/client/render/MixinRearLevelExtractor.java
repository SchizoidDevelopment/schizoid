package dev.lyzev.schizoid.injection.mixin.minecraft.client.render;

import dev.lyzev.api.opengl.RearViewPipeline;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = LevelExtractor.class, priority = 900)
public abstract class MixinRearLevelExtractor {
    @Redirect(method = "*", at = @At(value = "FIELD", target = "Lnet/minecraft/client/Minecraft;gameRenderer:Lnet/minecraft/client/renderer/GameRenderer;", opcode = Opcodes.GETFIELD), require = 0)
    private GameRenderer schizoid$routeCamera(Minecraft minecraft) {
        return RearViewPipeline.gameRendererFor((LevelExtractor) (Object) this, minecraft.gameRenderer);
    }

    @org.spongepowered.asm.mixin.Shadow @org.spongepowered.asm.mixin.Final private net.minecraft.client.renderer.LevelRenderer levelRenderer;

    // Sodium's merged checkRenderer helper reads the global primary renderer. Route that
    // optional access to the extractor's own renderer; vanilla contains no matching access.
    @Redirect(method = "*", at = @At(value = "FIELD", target = "Lnet/minecraft/client/Minecraft;levelRenderer:Lnet/minecraft/client/renderer/LevelRenderer;", opcode = Opcodes.GETFIELD), require = 0)
    private net.minecraft.client.renderer.LevelRenderer schizoid$routeOwnLevelRenderer(Minecraft minecraft) {
        return RearViewPipeline.isRearExtractor((LevelExtractor) (Object) this) ? levelRenderer : minecraft.levelRenderer;
    }

    @Inject(method = "extract", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/state/level/LevelRenderState;reset()V", shift = At.Shift.AFTER))
    private void schizoid$routeSodiumAfterSetup(net.minecraft.client.DeltaTracker delta, net.minecraft.client.Camera camera, float partial, CallbackInfo ci) {
        if (RearViewPipeline.isRearExtractor((LevelExtractor) (Object) this)) RearViewPipeline.bindSodiumExtractor((LevelExtractor) (Object) this);
    }

    @Inject(method = "extract", at = @At("RETURN"))
    private void schizoid$seedExistingChunks(net.minecraft.client.DeltaTracker delta, net.minecraft.client.Camera camera, float partial, CallbackInfo ci) {
        RearViewPipeline.seedLoadedChunks((LevelExtractor) (Object) this);
    }

    @Redirect(method = "extract", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientChunkCache;flipUpdateTrackingSets()V"))
    private void schizoid$skipTrackingFlip(ClientChunkCache cache) {
        if (!RearViewPipeline.isRearExtractor((LevelExtractor) (Object) this)) cache.flipUpdateTrackingSets();
    }

    @Redirect(method = "extract", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientChunkCache;addedEmptySections()Lit/unimi/dsi/fastutil/longs/LongOpenHashSet;"))
    private LongOpenHashSet schizoid$copyAddedEmpty(ClientChunkCache cache) { return copy(cache.addedEmptySections()); }
    @Redirect(method = "extract", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientChunkCache;removedEmptySections()Lit/unimi/dsi/fastutil/longs/LongOpenHashSet;"))
    private LongOpenHashSet schizoid$copyRemovedEmpty(ClientChunkCache cache) { return copy(cache.removedEmptySections()); }
    @Redirect(method = "extract", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientChunkCache;addedLoadedChunks()Lit/unimi/dsi/fastutil/longs/LongOpenHashSet;"))
    private LongOpenHashSet schizoid$copyAddedLoaded(ClientChunkCache cache) { return copy(cache.addedLoadedChunks()); }
    @Redirect(method = "extract", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientChunkCache;removedLoadedChunks()Lit/unimi/dsi/fastutil/longs/LongOpenHashSet;"))
    private LongOpenHashSet schizoid$copyRemovedLoaded(ClientChunkCache cache) { return copy(cache.removedLoadedChunks()); }
    private LongOpenHashSet copy(LongOpenHashSet set) { return RearViewPipeline.isRearExtractor((LevelExtractor) (Object) this) ? new LongOpenHashSet(set) : set; }

    @Inject(method = "allChanged", at = @At("RETURN"))
    private void schizoid$forwardAllChanged(CallbackInfo ci) { RearViewPipeline.forwardAllChanged((LevelExtractor) (Object) this); }
    @Inject(method = "resetSampler", at = @At("RETURN"))
    private void schizoid$forwardSampler(CallbackInfo ci) { RearViewPipeline.forwardSampler((LevelExtractor) (Object) this); }
    @Inject(method = "setSectionDirty(III)V", at = @At("RETURN"))
    private void schizoid$forwardSection(int x, int y, int z, CallbackInfo ci) { RearViewPipeline.forwardSection((LevelExtractor) (Object) this, x, y, z); }
    @Inject(method = "setSectionDirtyWithNeighbors", at = @At("RETURN"))
    private void schizoid$forwardNeighbors(int x, int y, int z, CallbackInfo ci) { RearViewPipeline.forwardNeighbors((LevelExtractor) (Object) this, x, y, z); }
    @Inject(method = "setSectionRangeDirty", at = @At("RETURN"))
    private void schizoid$forwardRange(int x1, int y1, int z1, int x2, int y2, int z2, CallbackInfo ci) { RearViewPipeline.forwardRange((LevelExtractor) (Object) this, x1,y1,z1,x2,y2,z2); }
    @Inject(method = "blockChanged", at = @At("RETURN"))
    private void schizoid$forwardBlock(net.minecraft.core.BlockPos pos, int flags, CallbackInfo ci) { RearViewPipeline.forwardBlock((LevelExtractor) (Object) this, pos, flags); }
    @Inject(method = "setBlockDirty(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/state/BlockState;)V", at = @At("RETURN"))
    private void schizoid$forwardBlockState(net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState oldState, net.minecraft.world.level.block.state.BlockState newState, CallbackInfo ci) { RearViewPipeline.forwardBlockState((LevelExtractor) (Object) this, pos, oldState, newState); }
    @Inject(method = "setBlocksDirty", at = @At("RETURN"))
    private void schizoid$forwardBlocks(int x1, int y1, int z1, int x2, int y2, int z2, CallbackInfo ci) { RearViewPipeline.forwardBlocks((LevelExtractor) (Object) this, x1,y1,z1,x2,y2,z2); }
    @Inject(method = "onResourceManagerReload", at = @At("RETURN"))
    private void schizoid$forwardReload(net.minecraft.server.packs.resources.ResourceManager manager, CallbackInfo ci) { RearViewPipeline.forwardReload((LevelExtractor) (Object) this, manager); }
}
