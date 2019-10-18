package org.valkyrienskies.mod.common.ship_handling;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.world.World;
import org.valkyrienskies.mod.common.multithreaded.VSThread;
import org.valkyrienskies.mod.common.physics.management.physo.PhysicsObject;
import org.valkyrienskies.mod.common.physmanagement.shipdata.ShipData;

import javax.annotation.Nonnull;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class WorldServerShipManager implements IPhysObjectWorld {

    private transient World world;
    private transient Map<EntityPlayer, List<ShipData>> playerToWatchingShips;
    private transient VSThread physicsThread;

    public WorldServerShipManager(World world) {
        this.world = world;
        this.playerToWatchingShips = new HashMap<>();
        this.physicsThread = new VSThread(this.world);
        this.physicsThread.start();
    }
    @Override
    public void onWorldUnload() {
        this.world = null;
        // Just to avoid memory leaks.
        this.playerToWatchingShips.clear();
        this.playerToWatchingShips = null;
        this.physicsThread.kill();
    }

    @Override
    public PhysicsObject createPhysObjectFromData(ShipData data) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean removePhysObject(ShipData data) {
        throw new UnsupportedOperationException();
    }

    @Override
    public PhysicsObject getPhysObjectFromData(ShipData data) {
        throw new UnsupportedOperationException();
    }

    @Nonnull
    @Override
    public List<PhysicsObject> getNearbyPhysObjects(AxisAlignedBB toCheck) {
        throw new UnsupportedOperationException();
    }

    public void tick() {
        // Does nothing for now, will eventually be used when ships are no longer entities.
    }

    public World getWorld() {
        return world;
    }

    public VSThread getPhysicsThread() {
        return this.physicsThread;
    }
}
