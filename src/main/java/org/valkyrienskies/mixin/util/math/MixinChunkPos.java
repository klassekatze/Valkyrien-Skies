package org.valkyrienskies.mixin.util.math;

import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.valkyrienskies.mod.common.ships.ship_world.PhysicsObject;
import org.valkyrienskies.mod.common.util.ValkyrienUtils;
import valkyrienwarfare.api.TransformType;

import java.util.Optional;

/**
 * Necessary for now. I just wish Mojang would delete the distance function.
 */
@Mixin(ChunkPos.class)
public abstract class MixinChunkPos {

    private static final double TOO_MUCH_DISTANCE = 100000;
    @Shadow
    @Final
    public int x;

    @Shadow
    @Final
    public int z;

    /**
     * This is easier to have as an overwrite because there's less laggy hackery to be done then :P
     *
     * @author DaPorkchop_
     */
    @Overwrite
    public double getDistanceSq(Entity entityIn) {
        double d0 = this.x * 16 + 8;
        double d1 = this.z * 16 + 8;
        double d2 = d0 - entityIn.posX;
        double d3 = d1 - entityIn.posZ;
        double vanilla = d2 * d2 + d3 * d3;

        // A big number
        if (vanilla < TOO_MUCH_DISTANCE) {
            return vanilla;
        }

        try {
            Optional<PhysicsObject> physicsObject = ValkyrienUtils.getPhysoManagingBlock(entityIn.world,
                new BlockPos(d0, 127, d1));

            if (physicsObject.isPresent()) {
                Vector3d entityPosInLocal = new Vector3d(entityIn.posX, entityIn.posY, entityIn.posZ);
                // RotationMatrices.applyTransform(wrapper.wrapping.coordTransform.wToLTransform,
                // entityPosInLocal);
                physicsObject.get()
                    .getShipTransformationManager()
                    .getCurrentTickTransform()
                    .transformPosition(entityPosInLocal, TransformType.GLOBAL_TO_SUBSPACE);
                entityPosInLocal.sub(d0, entityPosInLocal.y, d1);
                return entityPosInLocal.lengthSquared();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        return vanilla;
    }

}
