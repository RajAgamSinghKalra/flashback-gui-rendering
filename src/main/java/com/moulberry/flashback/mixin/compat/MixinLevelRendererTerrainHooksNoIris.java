package com.moulberry.flashback.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.visuals.ShaderManager;
import com.moulberry.flashback.state.EditorState;
import com.moulberry.flashback.state.EditorStateManager;
import com.moulberry.mixinconstraints.annotations.IfModAbsent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;

@IfModAbsent("iris")
@Mixin(value = LevelRenderer.class, priority = 1100)
public abstract class MixinLevelRendererTerrainHooksNoIris {

    @Unique
    private GpuTexture flashback$roundAlphaBuffer = null;
    @Unique
    private GpuTextureView flashback$roundAlphaBufferView = null;

    @Inject(method = "close", at = @At("HEAD"))
    public void flashback$closeTerrainHooks(CallbackInfo ci) {
        if (this.flashback$roundAlphaBuffer != null) {
            this.flashback$roundAlphaBuffer.close();
            this.flashback$roundAlphaBuffer = null;
        }
        if (this.flashback$roundAlphaBufferView != null) {
            this.flashback$roundAlphaBufferView.close();
            this.flashback$roundAlphaBufferView = null;
        }
    }

    @WrapOperation(method = "lambda$addMainPass$0", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;renderGroup(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayerGroup;Lcom/mojang/blaze3d/textures/GpuSampler;)V"))
    public void flashback$renderChunkGroup(ChunkSectionsToRender instance, ChunkSectionLayerGroup chunkSectionLayerGroup,
                                           GpuSampler gpuSampler, Operation<Void> original) {
        EditorState editorState = EditorStateManager.getCurrent();
        if (editorState != null && !editorState.replayVisuals.renderBlocks) {
            return;
        }

        original.call(instance, chunkSectionLayerGroup, gpuSampler);

        if (chunkSectionLayerGroup == ChunkSectionLayerGroup.OPAQUE && Flashback.isExporting() && Flashback.EXPORT_JOB.getSettings().transparent()) {
            RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();

            if (this.flashback$roundAlphaBuffer == null) {
                this.flashback$roundAlphaBuffer = RenderSystem.getDevice().createTexture(() -> "flashback round alpha buffer",
                    GpuTexture.USAGE_RENDER_ATTACHMENT, GpuFormat.RGBA8_UNORM, main.width, main.height, 1, 1);
                this.flashback$roundAlphaBufferView = RenderSystem.getDevice().createTextureView(this.flashback$roundAlphaBuffer);
            } else if (this.flashback$roundAlphaBuffer.getWidth(0) != main.width || this.flashback$roundAlphaBuffer.getHeight(0) != main.height) {
                this.flashback$roundAlphaBuffer.close();
                this.flashback$roundAlphaBufferView.close();
                this.flashback$roundAlphaBuffer = RenderSystem.getDevice().createTexture(() -> "flashback round alpha buffer",
                    GpuTexture.USAGE_RENDER_ATTACHMENT, GpuFormat.RGBA8_UNORM, main.width, main.height, 1, 1);
                this.flashback$roundAlphaBufferView = RenderSystem.getDevice().createTextureView(this.flashback$roundAlphaBuffer);
            }

            try (RenderPass renderPass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "flashback round alpha render pass 1", this.flashback$roundAlphaBufferView, Optional.empty())) {
                renderPass.setPipeline(RenderSystem.getCompiledPipeline(ShaderManager.BLIT_SCREEN));
                RenderSystem.bindDefaultUniforms(renderPass);
                renderPass.setUniform("InSampler", main.getColorTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
                renderPass.draw(3, 0, 0, 0);
            }

            try (RenderPass renderPass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "flashback round alpha render pass 2", main.getColorTextureView(), Optional.empty())) {
                renderPass.setPipeline(RenderSystem.getCompiledPipeline(ShaderManager.BLIT_SCREEN_ROUND_ALPHA));
                RenderSystem.bindDefaultUniforms(renderPass);
                renderPass.setUniform("InSampler", this.flashback$roundAlphaBufferView, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
                renderPass.draw(3, 0, 0, 0);
            }
        }
    }

}
