package me.eigenraven.lwjgl3ify.mixins.early.game;

import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import me.eigenraven.lwjgl3ify.lighting.ClientBlockLight;

@Mixin(Chunk.class)
public class MixinChunkLightChecks {

    @Shadow
    public World worldObj;

    @Inject(method = "fillChunk", at = @At("RETURN"), require = 1)
    private void lwjgl3ify$completeReceivedBlockLight(CallbackInfo ci) {
        if (worldObj.isRemote) {
            // Both chunk packet handlers fill the chunk before scheduling its
            // render update. Unlike resetRelightChecks, this also runs for full
            // surface-world packets. Keep vanilla's subsequent checks intact.
            ClientBlockLight.repair((Chunk) (Object) this);
        }
    }
}
