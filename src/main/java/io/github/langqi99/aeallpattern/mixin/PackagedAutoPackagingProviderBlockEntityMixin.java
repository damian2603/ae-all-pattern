package io.github.langqi99.aeallpattern.mixin;


import io.github.langqi99.aeallpattern.machine.PackagedAutoAggregateCompat;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "thelm.packagedauto.integration.appeng.blockentity.AEPackagingProviderBlockEntity", remap = false)
public abstract class PackagedAutoPackagingProviderBlockEntityMixin {
    @Unique private boolean aeallpattern$initialCatalogRefresh;

    @Inject(method = "tick", at = @At("HEAD"), remap = false)
    private void aeallpattern$restoreCatalogAfterLoad(CallbackInfo ci) {
        if (aeallpattern$initialCatalogRefresh
                || !(((BlockEntity) (Object) this).getLevel() instanceof ServerLevel)) {
            return;
        }
        aeallpattern$initialCatalogRefresh = true;
        PackagedAutoAggregateCompat.refreshLoadedProvider(this);
    }

    @Inject(method = "getAvailablePatterns", at = @At("RETURN"), cancellable = true)
    private void aeallpattern$usePackageWorkflow(CallbackInfoReturnable<List<?>> callback) {
        callback.setReturnValue(PackagedAutoAggregateCompat.usePackageWorkflow(this, callback.getReturnValue()));
    }
}
