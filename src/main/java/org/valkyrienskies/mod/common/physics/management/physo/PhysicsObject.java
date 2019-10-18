/*
 * Adapted from the Wizardry License
 *
 * Copyright (c) 2015-2019 the Valkyrien Skies team
 *
 * Permission is hereby granted to any persons and/or organizations using this software to copy, modify, merge, publish, and distribute it.
 * Said persons and/or organizations are not allowed to use the software or any derivatives of the work for commercial use or any other means to generate income unless it is to be used as a part of a larger project (IE: "modpacks"), nor are they allowed to claim this software as their own.
 *
 * The persons and/or organizations are also disallowed from sub-licensing and/or trademarking this software without explicit permission from the Valkyrien Skies team.
 *
 * Any persons and/or organizations using this software must disclose their source code and have it publicly available, include this license, provide sufficient credit to the original authors of the project (IE: The Valkyrien Skies team), as well as provide a link to the original project.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON INFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 *
 */

package org.valkyrienskies.mod.common.physics.management.physo;

import com.google.common.collect.Sets;
import gnu.trove.iterator.TIntIterator;
import gnu.trove.list.array.TIntArrayList;
import io.netty.buffer.ByteBuf;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.server.SPacketChunkData;
import net.minecraft.network.play.server.SPacketUnloadChunk;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.IThreadListener;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.BlockPos.MutableBlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.ChunkCache;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraft.world.gen.ChunkProviderServer;
import org.valkyrienskies.addon.control.nodenetwork.INodeController;
import org.valkyrienskies.fixes.IPhysicsChunk;
import org.valkyrienskies.mod.client.render.PhysObjectRenderManager;
import org.valkyrienskies.mod.common.ValkyrienSkiesMod;
import org.valkyrienskies.mod.common.config.VSConfig;
import org.valkyrienskies.mod.common.coordinates.ISubspace;
import org.valkyrienskies.mod.common.coordinates.ISubspaceProvider;
import org.valkyrienskies.mod.common.coordinates.ImplSubspace;
import org.valkyrienskies.mod.common.coordinates.ShipTransform;
import org.valkyrienskies.mod.common.entity.PhysicsWrapperEntity;
import org.valkyrienskies.mod.common.math.Quaternion;
import org.valkyrienskies.mod.common.math.Vector;
import org.valkyrienskies.mod.common.multithreaded.TickSyncCompletableFuture;
import org.valkyrienskies.mod.common.network.WrapperPositionMessage;
import org.valkyrienskies.mod.common.physics.BlockPhysicsDetails;
import org.valkyrienskies.mod.common.physics.PhysicsCalculations;
import org.valkyrienskies.mod.common.physics.collision.meshing.IVoxelFieldAABBMaker;
import org.valkyrienskies.mod.common.physics.collision.meshing.NaiveVoxelFieldAABBMaker;
import org.valkyrienskies.mod.common.physics.management.ShipTransformationManager;
import org.valkyrienskies.mod.common.physics.management.chunkcache.ClaimedChunkCacheController;
import org.valkyrienskies.mod.common.physics.management.chunkcache.SurroundingChunkCacheController;
import org.valkyrienskies.mod.common.physmanagement.chunk.VSChunkClaim;
import org.valkyrienskies.mod.common.physmanagement.relocation.DetectorManager;
import org.valkyrienskies.mod.common.physmanagement.relocation.DetectorManager.DetectorIDs;
import org.valkyrienskies.mod.common.physmanagement.relocation.MoveBlocks;
import org.valkyrienskies.mod.common.physmanagement.relocation.SpatialDetector;
import org.valkyrienskies.mod.common.tileentity.TileEntityPhysicsInfuser;
import org.valkyrienskies.mod.common.util.ValkyrienNBTUtils;
import org.valkyrienskies.mod.common.util.ValkyrienUtils;
import valkyrienwarfare.api.IPhysicsEntity;
import valkyrienwarfare.api.TransformType;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The heart and soul of this mod. The physics object does everything from custom collision, block
 * interactions, physics, networking, rendering, and more!
 *
 * @author thebest108
 */
@Accessors(fluent = false)
public class PhysicsObject implements ISubspaceProvider, IPhysicsEntity {

    @Getter
    private final PhysicsWrapperEntity wrapperEntity;

    //private final PhysoPositionData posdata;
    //private final PhysoMetadata metadata;

    @Getter
    private final List<EntityPlayerMP> watchingPlayers;
    private final ISubspace shipSubspace;
    private final Set<INodeController> physicsControllers;
    private final Set<INodeController> physicsControllersImmutable;
    // Used to iterate over the ship blocks extremely quickly by taking advantage of the cache
    @Getter
    private final TIntArrayList blockPositionsGameTick;
    @Getter
    private PhysObjectRenderManager shipRenderer;
    /**
     * Just a random block position in the ship. Used to correct floating point errors and keep
     * track of the ship.
     */
    @Getter
    @Setter
    private BlockPos referenceBlockPos;
    @Getter
    @Setter
    private Vector centerCoord;
    @Getter
    @Setter
    private ShipTransformationManager shipTransformationManager;
    @Getter
    @Setter
    private PhysicsCalculations physicsProcessor;
    /**
     * Has to be concurrent, only exists properly on the server. Do not use this for anything client
     * side! Contains all of the non-air block positions on the ship. This is used for generating
     * AABBs and deconstructing the ship.
     */
    @Getter
    private Set<BlockPos> blockPositions;
    @Getter
    @Setter
    private boolean isPhysicsEnabled = false;
    @Getter
    @Setter
    private DetectorIDs detectorID;
    // The closest Chunks to the Ship cached in here
    private SurroundingChunkCacheController cachedSurroundingChunks;
    // TODO: Make for re-organizing these to make Ship sizes Dynamic
    @Getter
    @Setter
    private VSChunkClaim ownedChunks;
    /**
     * Used for faster memory access to the Chunks this object 'owns'
     */
    @Getter
    private ClaimedChunkCacheController claimedChunkCache;
    @Getter
    @Setter
    private AxisAlignedBB shipBoundingBox;
    /**
     * If this PhysicsObject needs to update the collision cache immediately
     */
    @Setter
    @Getter
    private boolean needsCollisionCacheUpdate = true;
    @Getter
    @Setter
    private BlockPos physicsInfuserPos = null;
    private boolean shipAligningToGrid = false;
    @Getter
    private boolean isFullyLoaded = false;
    @Getter
    private IVoxelFieldAABBMaker voxelFieldAABBMaker; // Used to quickly make aabb's
    @Getter
    private final World world;

    public PhysicsObject(PhysicsWrapperEntity host) {
        this.wrapperEntity = host;
        this.world = host.world;
        if (host.world.isRemote) {
            this.shipRenderer = new PhysObjectRenderManager(this);
        }
        // We need safe access to this across multiple threads.
        this.blockPositions = ConcurrentHashMap.newKeySet();
        this.shipBoundingBox = Entity.ZERO_AABB;
        this.watchingPlayers = new ArrayList<>();
        this.shipSubspace = new ImplSubspace(this);
        this.physicsControllers = Sets.newConcurrentHashSet();
        this.physicsControllersImmutable = Collections.unmodifiableSet(this.physicsControllers);
        this.blockPositionsGameTick = new TIntArrayList();
        this.cachedSurroundingChunks = new SurroundingChunkCacheController(this);
        this.voxelFieldAABBMaker = null;
    }

    public void onSetBlockState(IBlockState oldState, IBlockState newState, BlockPos posAt) {
        // If the world is remote, or the block is not within the claimed chunks, ignore it!
        if (getWorld().isRemote || !getOwnedChunks().containsBlock(posAt)) {
            return;
        }

        // If the block here is not to be made with physics, just treat it like you'd
        // treat AIR blocks.
        if (oldState != null && BlockPhysicsDetails.blocksToNotPhysicsInfuse
            .contains(oldState.getBlock())) {
            oldState = Blocks.AIR.getDefaultState();
        }
        if (newState != null && BlockPhysicsDetails.blocksToNotPhysicsInfuse
            .contains(newState.getBlock())) {
            newState = Blocks.AIR.getDefaultState();
        }

        boolean isOldAir = oldState == null || oldState.getBlock().equals(Blocks.AIR);
        boolean isNewAir = newState == null || newState.getBlock().equals(Blocks.AIR);

        if (isNewAir) {
            boolean removed = getBlockPositions().remove(posAt);
            voxelFieldAABBMaker.removeVoxel(posAt.getX(), posAt.getY(), posAt.getZ());
            if (removed) {
                this.blockPositionsGameTick.remove(this.getBlockPosToIntRelToShip(posAt));
            }
        }

        if (isOldAir && !isNewAir) {
            boolean isAdded = getBlockPositions().add(posAt);
            voxelFieldAABBMaker.addVoxel(posAt.getX(), posAt.getY(), posAt.getZ());
            if (isAdded) {
                this.blockPositionsGameTick.add(this.getBlockPosToIntRelToShip(posAt));
            }

            int chunkRelativeX = (posAt.getX() >> 4) - getOwnedChunks().minX();
            int chunkRelativeZ = (posAt.getZ() >> 4) - getOwnedChunks().minZ();
        }

        if (getBlockPositions().isEmpty()) {
            destroy();
        }

        if (getPhysicsProcessor() != null) {
            getPhysicsProcessor().onSetBlockState(oldState, newState, posAt);
        }
    }

    public void destroy() {
        getWrapperEntity().setDead();
        List<EntityPlayerMP> watchersCopy = new ArrayList<>(getWatchingPlayers());
        for (int x = getOwnedChunks().minX(); x <= getOwnedChunks().maxX(); x++) {
            for (int z = getOwnedChunks().minZ(); z <= getOwnedChunks().maxZ(); z++) {
                SPacketUnloadChunk unloadPacket = new SPacketUnloadChunk(x, z);
                for (EntityPlayerMP wachingPlayer : watchersCopy) {
                    wachingPlayer.connection.sendPacket(unloadPacket);
                }
            }
            // NOTICE: This method isnt being called to avoid the
            // watchingPlayers.remove(player) call, which is a waste of CPU time
            // onPlayerUntracking(wachingPlayer);
        }
        getWatchingPlayers().clear();
        ValkyrienUtils.getQueryableData(getWorld()).removeShip(this.getWrapperEntity());
        ValkyrienSkiesMod.VS_PHYSICS_MANAGER.onShipUnload(getWrapperEntity());
    }

    public void claimNewChunks() {
        setOwnedChunks(ValkyrienUtils.getShipChunkAllocator(getWorld()).allocateNextChunkClaim());
        ValkyrienUtils.getQueryableData(getWorld()).addShip(getWrapperEntity());
    }

    /**
     * Generates the new chunks
     */
    public TickSyncCompletableFuture<Void> assembleShipAsOrderedByPlayer(EntityPlayer player) {
        if (getWorld().isRemote) {
            throw new IllegalStateException("This method cannot be invoked on client side!");
        }
        if (!(getWorld() instanceof WorldServer)) {
            throw new IllegalStateException(
                "The world " + getWorld() + " wasn't an instance of WorldServer");
        }

        BlockPos centerInWorld = new BlockPos(getWrapperEntity().posX,
            getWrapperEntity().posY, getWrapperEntity().posZ);

        // The thread the tick sync will execute on.
        IThreadListener worldServerThread = (WorldServer) getWorld();

        return TickSyncCompletableFuture
            .supplyAsync(
                () -> DetectorManager.getDetectorFor(getDetectorID(), centerInWorld, getWorld(),
                    VSConfig.maxShipSize + 1, true))
            .thenAcceptSync(detector -> {
                if (detector.foundSet.size() > VSConfig.maxShipSize || detector.cleanHouse) {
                    System.err.println("Ship too big or bedrock detected!");
                    if (player != null) {
                        player.sendMessage(new TextComponentString(
                            "Ship construction canceled because its exceeding the ship size limit; "
                                +
                                "or because it's attached to bedrock. " +
                                "Raise it with /physsettings maxshipsize [number]"));
                    }
                    getWrapperEntity().setDead();
                    return;
                }
                assembleShip(player, detector, centerInWorld);

                markFullyLoaded();
            }, worldServerThread);
    }

    /**
     * Creates the PhysicsProcessor object before any data gets loaded into it; can be overridden to
     * change the class of the Object
     */
    private void createPhysicsCalculations() {
        if (getPhysicsProcessor() == null) {
            setPhysicsProcessor(new PhysicsCalculations(this));
        }
    }

    private void assembleShip(EntityPlayer player, SpatialDetector detector,
        BlockPos centerInWorld) {

        this.setPhysicsEnabled(true);
        MutableBlockPos pos = new MutableBlockPos();

        claimNewChunks();

        claimedChunkCache = new ClaimedChunkCacheController(this, false);

        assignChunkPhysicObject();

        setReferenceBlockPos(getOwnedChunks().getRegionCenter());
        voxelFieldAABBMaker = new NaiveVoxelFieldAABBMaker(referenceBlockPos.getX(),
            referenceBlockPos.getZ());

        setCenterCoord(new Vector(getReferenceBlockPos().getX() + .5,
            getReferenceBlockPos().getY() + .5,
            getReferenceBlockPos().getZ() + .5));

        createPhysicsCalculations();

        TIntIterator iter = detector.foundSet.iterator();
        BlockPos centerDifference = getReferenceBlockPos().subtract(centerInWorld);

        MutableBlockPos oldPos = new MutableBlockPos();
        MutableBlockPos newPos = new MutableBlockPos();

        // First copy all the blocks from ship to world.
        while (iter.hasNext()) {
            int i = iter.next();
            SpatialDetector.setPosWithRespectTo(i, centerInWorld, oldPos);
            SpatialDetector.setPosWithRespectTo(i, centerInWorld, newPos);
            newPos.setPos(newPos.getX() + centerDifference.getX(),
                newPos.getY() + centerDifference.getY(),
                newPos.getZ() + centerDifference.getZ());

            MoveBlocks.copyBlockToPos(getWorld(), oldPos, newPos, Optional.of(this));
            voxelFieldAABBMaker.addVoxel(newPos.getX(), newPos.getY(), newPos.getZ());
        }
        this.physicsInfuserPos = this.physicsInfuserPos.add(centerDifference);

        // First we destroy all the tile entities we copied.
        iter = detector.foundSet.iterator();
        while (iter.hasNext()) {
            int i = iter.next();
            SpatialDetector.setPosWithRespectTo(i, centerInWorld, pos);
            TileEntity tile = getWorld().getTileEntity(pos);
            if (tile != null && !tile.isInvalid()) {
                try {
                    tile.invalidate();
                } catch (Exception e) {
                    e.printStackTrace();
                }
                try {
                    getWorld().removeTileEntity(pos);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }

        // Then we destroy all the blocks we copied
        iter = detector.foundSet.iterator();
        while (iter.hasNext()) {
            int i = iter.next();
            SpatialDetector.setPosWithRespectTo(i, centerInWorld, pos);
            getWorld().setBlockState(pos, Blocks.AIR.getDefaultState(), 2);
        }

        // We NEED this to fix ship lighting. If this code was removed then ships would have lighting artifacts all
        // over them.
        for (int x = getOwnedChunks().minX(); x <= getOwnedChunks().maxX(); x++) {
            for (int z = getOwnedChunks().minZ(); z <= getOwnedChunks().maxZ(); z++) {
                claimedChunkCache.getChunkAt(x, z).checkLight();
            }
        }

        getWrapperEntity().posX += .5;
        getWrapperEntity().posY += .5;
        getWrapperEntity().posZ += .5;

        // Some extra ship crap at the end.
        detectBlockPositions();
        setShipTransformationManager(new ShipTransformationManager(this));

        getPhysicsProcessor().updateParentCenterOfMass();
    }

    public void preloadNewPlayers() {
        Set<EntityPlayerMP> newWatchers = getPlayersThatJustWatched();
        for (Chunk[] chunkArray : claimedChunkCache.getCacheArray()) {
            for (Chunk chunk : chunkArray) {
                SPacketChunkData data = new SPacketChunkData(chunk, 65535);
                for (EntityPlayerMP player : newWatchers) {
                    player.connection.sendPacket(data);
                    ((WorldServer) getWorld()).getEntityTracker()
                        .sendLeashedEntitiesInChunk(player, chunk);
                }
            }
        }
    }

    /**
     * TODO: Make this further get the player to stop all further tracking of those physObject
     *
     * @param untracking EntityPlayer that stopped tracking
     */
    public void onPlayerUntracking(EntityPlayer untracking) {
        getWatchingPlayers().remove(untracking);
        for (int x = getOwnedChunks().minX(); x <= getOwnedChunks().maxX(); x++) {
            for (int z = getOwnedChunks().minZ(); z <= getOwnedChunks().maxZ(); z++) {
                SPacketUnloadChunk unloadPacket = new SPacketUnloadChunk(x, z);
                ((EntityPlayerMP) untracking).connection.sendPacket(unloadPacket);
            }
        }
    }

    /**
     * Called when this entity has been unloaded from the world
     */
    public void onThisUnload() {
        if (!getWorld().isRemote) {
            unloadShipChunksFromWorld();
        } else {
            getShipRenderer().killRenderers();
        }
    }

    public void unloadShipChunksFromWorld() {
        ChunkProviderServer provider = (ChunkProviderServer) getWorld().getChunkProvider();
        for (int x = getOwnedChunks().minX(); x <= getOwnedChunks().maxX(); x++) {
            for (int z = getOwnedChunks().minZ(); z <= getOwnedChunks().maxZ(); z++) {
                provider.queueUnload(claimedChunkCache.getChunkAt(x, z));
            }
        }
    }

    private Set<EntityPlayerMP> getPlayersThatJustWatched() {
        Set<EntityPlayerMP> newPlayers = new HashSet<>();
        for (Object o : ((WorldServer) getWorld()).getEntityTracker()
            .getTrackingPlayers(getWrapperEntity())) {
            EntityPlayerMP player = (EntityPlayerMP) o;
            if (!getWatchingPlayers().contains(player)) {
                newPlayers.add(player);
                getWatchingPlayers().add(player);
            }
        }
        return newPlayers;
    }

    public void onTick() {
        if (!getWorld().isRemote) {
            TileEntity te = getWorld().getTileEntity(this.physicsInfuserPos);
            boolean shouldDeconstructShip;
            if (te instanceof TileEntityPhysicsInfuser) {
                TileEntityPhysicsInfuser physicsCore = (TileEntityPhysicsInfuser) te;
                // Mark for deconstruction
                shouldDeconstructShip =
                    !physicsCore.canMaintainShip() || physicsCore.isTryingToDisassembleShip();
                shipAligningToGrid =
                    !physicsCore.canMaintainShip() || physicsCore.isTryingToAlignShip();
                setPhysicsEnabled(
                    !physicsCore.canMaintainShip() || physicsCore.isPhysicsEnabled());
            } else {
                // Mark for deconstruction
                shipAligningToGrid = true;
                shouldDeconstructShip = true;
                setPhysicsEnabled(true);
            }

            if (shouldDeconstructShip) {
                this.tryToDeconstructShip();
            }
        }

        this.setNeedsCollisionCacheUpdate(false);
    }

    public void onPostTick() {
        if (!getWrapperEntity().isDead && !getWrapperEntity().world.isRemote) {
            ValkyrienUtils.getQueryableData(getWorld()).updateShipPosition(this.getWrapperEntity());
        }
    }

    /**
     * Updates the position and orientation of the client according to the data sent from the
     * server.
     */
    public void onPostTickClient() {
        WrapperPositionMessage toUse = getShipTransformationManager().serverBuffer
            .pollForClientTransform();
        if (toUse != null) {
            toUse.applySmoothLerp(this, .6D);
        }

        getShipTransformationManager().updateAllTransforms(false, false, true);
    }

    public void updateChunkCache() {
        cachedSurroundingChunks.updateChunkCache();
    }

    public void loadClaimedChunks() {
        claimedChunkCache = new ClaimedChunkCacheController(this, true);

        assignChunkPhysicObject();
        setReferenceBlockPos(getOwnedChunks().getRegionCenter());
        voxelFieldAABBMaker = new NaiveVoxelFieldAABBMaker(referenceBlockPos.getX(),
            referenceBlockPos.getZ());
        setShipTransformationManager(new ShipTransformationManager(this));
        if (!getWorld().isRemote) {
            createPhysicsCalculations();
            // The client doesn't need to keep track of this.
            detectBlockPositions();

        }

        // getShipTransformationManager().updateAllTransforms(false, false);
    }

    // Generates the blockPos array; must be loaded DIRECTLY after the chunks are
    // setup
    public void detectBlockPositions() {
        // int minChunkX = claimedChunks[0][0].x;
        // int minChunkZ = claimedChunks[0][0].z;
        int chunkX, chunkZ, index, x, y, z;
        Chunk chunk;
        ExtendedBlockStorage storage;
        Chunk[][] claimedChunks = claimedChunkCache.getCacheArray();
        int airStateIndex = Block.getStateId(Blocks.AIR.getDefaultState());

        for (chunkX = claimedChunks.length - 1; chunkX > -1; chunkX--) {
            for (chunkZ = claimedChunks[0].length - 1; chunkZ > -1; chunkZ--) {
                chunk = claimedChunks[chunkX][chunkZ];
                if (chunk != null) {
                    for (index = 0; index < 16; index++) {
                        storage = chunk.getBlockStorageArray()[index];
                        if (storage != null) {
                            for (y = 0; y < 16; y++) {
                                for (x = 0; x < 16; x++) {
                                    for (z = 0; z < 16; z++) {
                                        if (storage.data.storage
                                            .getAt(y << 8 | z << 4 | x)
                                            != airStateIndex) {
                                            BlockPos pos = new BlockPos(chunk.x * 16 + x,
                                                index * 16 + y,
                                                chunk.z * 16 + z);
                                            getBlockPositions().add(pos);
                                            blockPositionsGameTick
                                                .add(this.getBlockPosToIntRelToShip(pos));
                                            voxelFieldAABBMaker
                                                .addVoxel(pos.getX(), pos.getY(), pos.getZ());
                                            if (BlockPhysicsDetails.isBlockProvidingForce(
                                                getWorld().getBlockState(pos), pos, getWorld())) {
                                                getPhysicsProcessor()
                                                    .addPotentialActiveForcePos(pos);
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

    }

    public boolean ownsChunk(int chunkX, int chunkZ) {
        return getOwnedChunks().containsChunk(chunkX, chunkZ);
    }

    public void writeToNBTTag(NBTTagCompound compound) {
        getOwnedChunks().writeToNBT(compound);
        ValkyrienNBTUtils.writeVectorToNBT("c", getCenterCoord(), compound);
        getShipTransformationManager().getCurrentTickTransform()
            .writeToNBT(compound, "current_tick_transform");
        compound.setBoolean("doPhysics", isPhysicsEnabled/* isPhysicsEnabled() */);

        getPhysicsProcessor().writeToNBTTag(compound);

        // TODO: This is occasionally crashing the Ship save
        // StringBuilder result = new StringBuilder("");
        // allowedUsers.forEach(s -> {
        //     result.append(s);
        //     result.append(";");
        // });
        // compound.setString("allowedUsers", result.substring(0, result.length() - 1));

        // Write and read AABB from NBT to speed things up.
        ValkyrienNBTUtils.writeAABBToNBT("collision_aabb", getShipBoundingBox(), compound);
        ValkyrienNBTUtils.writeBlockPosToNBT("physics_infuser_pos", physicsInfuserPos, compound);
    }

    /**
     * Begins to load the ship
     */
    public void readFromNBTTag(NBTTagCompound compound) {
        // This first
        setCenterCoord(ValkyrienNBTUtils.readVectorFromNBT("c", compound));
        // Then this second
        createPhysicsCalculations();
        assert getPhysicsProcessor() != null : "Insert error message here";

        setOwnedChunks(new VSChunkClaim(compound));
        ShipTransform savedTransform = ShipTransform
            .readFromNBT(compound, "current_tick_transform");

        if (savedTransform != null) {
            Vector centerOfMassInGlobal = new Vector(getCenterCoord());
            savedTransform.transform(centerOfMassInGlobal, TransformType.SUBSPACE_TO_GLOBAL);

            getWrapperEntity().posX = centerOfMassInGlobal.x;
            getWrapperEntity().posY = centerOfMassInGlobal.y;
            getWrapperEntity().posZ = centerOfMassInGlobal.z;

            Quaternion rotationQuaternion = savedTransform
                .createRotationQuaternion(TransformType.SUBSPACE_TO_GLOBAL);
            double[] angles = rotationQuaternion.toRadians();
            getWrapperEntity()
                .setPhysicsEntityRotation(Math.toDegrees(angles[0]), Math.toDegrees(angles[1]),
                    Math.toDegrees(angles[2]));
        }

        loadClaimedChunks();

        // After we have loaded which positions are stored in the ship; we load the physics calculations object.
        getPhysicsProcessor().readFromNBTTag(compound);

        this.setShipBoundingBox(ValkyrienNBTUtils.readAABBFromNBT("collision_aabb", compound));

        setPhysicsEnabled(compound.getBoolean("doPhysics"));
        physicsInfuserPos = ValkyrienNBTUtils.readBlockPosFromNBT("physics_infuser_pos", compound);

        markFullyLoaded();
    }

    public void readSpawnData(ByteBuf additionalData) {
        PacketBuffer modifiedBuffer = new PacketBuffer(additionalData);

        setOwnedChunks(new VSChunkClaim(modifiedBuffer.readInt(), modifiedBuffer.readInt(),
            modifiedBuffer.readInt()));

        double posX = modifiedBuffer.readDouble();
        double posY = modifiedBuffer.readDouble();
        double posZ = modifiedBuffer.readDouble();
        double pitch = modifiedBuffer.readDouble();
        double yaw = modifiedBuffer.readDouble();
        double roll = modifiedBuffer.readDouble();

        getWrapperEntity().setPhysicsEntityPositionAndRotation(posX, posY, posZ, pitch, yaw, roll);
        getWrapperEntity().physicsUpdateLastTickPositions();

        setCenterCoord(new Vector(modifiedBuffer));
        loadClaimedChunks();

        getShipRenderer().updateOffsetPos(getReferenceBlockPos());

        getShipTransformationManager().serverBuffer
            .pushMessage(new WrapperPositionMessage(this));

        if (modifiedBuffer.readBoolean()) {
            setPhysicsInfuserPos(modifiedBuffer.readBlockPos());
        }

        markFullyLoaded();
    }

    public void writeSpawnData(ByteBuf buffer) {
        PacketBuffer modifiedBuffer = new PacketBuffer(buffer);

        modifiedBuffer.writeInt(getOwnedChunks().getCenterX());
        modifiedBuffer.writeInt(getOwnedChunks().getCenterZ());
        modifiedBuffer.writeInt(getOwnedChunks().getRadius());

        modifiedBuffer.writeDouble(getWrapperEntity().posX);
        modifiedBuffer.writeDouble(getWrapperEntity().posY);
        modifiedBuffer.writeDouble(getWrapperEntity().posZ);

        modifiedBuffer.writeDouble(getWrapperEntity().getPitch());
        modifiedBuffer.writeDouble(getWrapperEntity().getYaw());
        modifiedBuffer.writeDouble(getWrapperEntity().getRoll());

        getCenterCoord().writeToByteBuf(modifiedBuffer);

        // Make a local copy to avoid potential data races
        BlockPos physicsInfuserPosLocal = getPhysicsInfuserPos();
        modifiedBuffer.writeBoolean(physicsInfuserPosLocal != null);
        if (physicsInfuserPosLocal != null) {
            modifiedBuffer.writeBlockPos(physicsInfuserPosLocal);
        }
    }

    /*
     * Encapsulation code past here.
     */

    /**
     * Sets the consecutive tick counter to 0.
     */
    public void resetConsecutiveProperTicks() {
        this.setNeedsCollisionCacheUpdate(true);
    }

    private void assignChunkPhysicObject() {
        for (int x = this.ownedChunks.minX(); x <= this.ownedChunks.maxX(); x++) {
            for (int z = this.ownedChunks.minZ(); z <= this.ownedChunks.maxZ(); z++) {
                ((IPhysicsChunk) getChunkAt(x, z)).setParentPhysicsObject(Optional.of(this));
            }
        }
    }

    /**
     * @return the cachedSurroundingChunks
     */
    public ChunkCache getCachedSurroundingChunks() {
        return cachedSurroundingChunks.cachedChunks();
    }

    @Override
    public ISubspace getSubspace() {
        return this.shipSubspace;
    }

    // ===== Keep track of all Node Processors in a concurrent Set =====
    public void onSetTileEntity(BlockPos pos, TileEntity tileentity) {
        if (tileentity instanceof INodeController) {
            physicsControllers.add((INodeController) tileentity);
        }
    }

    public void onRemoveTileEntity(BlockPos pos) {
        physicsControllers.removeIf(next -> next.getNodePos().equals(pos));
    }

    // Do not allow anything external to modify the physics controllers Set.
    public Set<INodeController> getPhysicsControllersInShip() {
        return physicsControllersImmutable;
    }

    private int getBlockPosToIntRelToShip(BlockPos pos) {
        return SpatialDetector
            .getHashWithRespectTo(pos.getX(), pos.getY(), pos.getZ(), this.referenceBlockPos);
    }

    void setBlockPosFromIntRelToShop(int pos, MutableBlockPos toSet) {
        SpatialDetector.setPosWithRespectTo(pos, this.referenceBlockPos, toSet);
    }

    /**
     * Returns true if this ship is aligned close enough to the grid that it is allowed to
     * deconstruct back to the world.
     */
    public boolean canShipBeDeconstructed() {
        ShipTransform zeroTransform = ShipTransform.createRotationTransform(0, 0, 0);
        // The quaternion with the world's orientation (which is zero because the world never moves
        // or rotates)
        Quaternion zeroQuat = zeroTransform
            .createRotationQuaternion(TransformType.SUBSPACE_TO_GLOBAL);
        // The quaternion with the ship's orientation
        Quaternion shipQuat = getShipTransformationManager().getCurrentTickTransform()
            .createRotationQuaternion(TransformType.SUBSPACE_TO_GLOBAL);
        double dotProduct = Quaternion.dotProduct(zeroQuat, shipQuat);
        // Calculate the angle between the two quaternions
        double anglesBetweenQuaternions = Math.toDegrees(Math.acos(dotProduct));
        // Only allow a ship to be deconstructed if the angle between the grid and its orientation is less than half a degree.
        return anglesBetweenQuaternions < .5;
    }

    public void tryToDeconstructShip() {
        // First check if the ship orientation is close to that of the grid; if it isn't then don't let this ship deconstruct.
        if (!canShipBeDeconstructed()) {
            return;
        }

        // We're pretty close to the grid; time 2 go.
        MutableBlockPos newPos = new MutableBlockPos();
        BlockPos centerDifference = new BlockPos(
            Math.round(centerCoord.x - getWrapperEntity().posX),
            Math.round(centerCoord.y - getWrapperEntity().posY),
            Math.round(centerCoord.z - getWrapperEntity().posZ));
        // First copy all the blocks from ship to world.

        for (BlockPos oldPos : this.blockPositions) {
            newPos.setPos(oldPos.getX() - centerDifference.getX(),
                oldPos.getY() - centerDifference.getY(), oldPos.getZ() - centerDifference.getZ());
            MoveBlocks.copyBlockToPos(getWorld(), oldPos, newPos, Optional.empty());
        }

        // Just delete the tile entities in ship to prevent any dupe bugs.
        for (BlockPos oldPos : this.blockPositions) {
            getWorld().removeTileEntity(oldPos);
        }

        // Delete old blocks. TODO: Used to use EMPTYCHUNK to do this but that causes crashes?
        for (int x = getOwnedChunks().minX(); x <= getOwnedChunks().maxX(); x++) {
            for (int z = getOwnedChunks().minZ(); z <= getOwnedChunks().maxZ(); z++) {
                Chunk chunk = new Chunk(getWorld(), x, z);
                chunk.setTerrainPopulated(true);
                chunk.setLightPopulated(true);
                claimedChunkCache.injectChunkIntoWorld(chunk, x, z, true);
                claimedChunkCache.setChunkAt(x, z, chunk);
            }
        }

        this.destroy();
    }

    public boolean getShipAligningToGrid() {
        return this.shipAligningToGrid;
    }

    // VS API Functions Begin:
    @Override
    public Vec3d rotateVector(Vec3d vector, TransformType transformType) {
        return this.getShipTransformationManager()
            .getCurrentTickTransform()
            .rotate(vector, transformType);
    }

    @Override
    public Vec3d transformVector(Vec3d vector, TransformType transformType) {
        return this.getShipTransformationManager()
            .getCurrentTickTransform()
            .transform(vector, transformType);
    }
    // VS API Functions End:

    /**
     * Gets the chunk at chunkX and chunkZ.
     *
     * @see ClaimedChunkCacheController#getChunkAt(int, int)
     */
    public Chunk getChunkAt(int chunkX, int chunkZ) {
        return claimedChunkCache.getChunkAt(chunkX, chunkZ);
    }

    private void markFullyLoaded() {
        getShipTransformationManager().updateAllTransforms(!getWorld().isRemote, true, true);
        isFullyLoaded = true;
    }
}
