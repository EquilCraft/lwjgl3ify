package me.eigenraven.lwjgl3ify.lighting;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;

/** Completes source light across loaded chunk borders before packet-triggered rendering. */
public final class ClientBlockLight {

    private final World world;
    private final Chunk chunk;
    private final Chunk[] neighbours = new Chunk[9];
    private final Map<Integer, Boolean> supported = new HashMap<>();
    private final int[][] queues = new int[16][];
    private final int[] sizes = new int[16];
    // Keep contributions from real sources separate from supplied light values.
    // An old bright value in the packet must never become a new emitter.
    private final byte[][][] propagated = new byte[9][16][];
    private final int originX;
    private final int originZ;

    private ClientBlockLight(Chunk chunk) {
        this.chunk = chunk;
        world = chunk.worldObj;
        originX = chunk.xPosition << 4;
        originZ = chunk.zPosition << 4;
        for (int z = 0; z < 3; z++) {
            for (int x = 0; x < 3; x++) {
                Chunk neighbour = world.getChunkFromChunkCoords(chunk.xPosition + x - 1, chunk.zPosition + z - 1);
                if (neighbour.isChunkLoaded && neighbour.isTerrainPopulated) {
                    neighbours[z * 3 + x] = neighbour;
                }
            }
        }
        neighbours[4] = chunk;
    }

    public static void repair(Chunk chunk) {
        new ClientBlockLight(chunk).run();
    }

    private void run() {
        ExtendedBlockStorage[] sections = chunk.getBlockStorageArray();
        for (int sy = 0; sy < sections.length; sy++) {
            ExtendedBlockStorage section = sections[sy];
            if (section == null) {
                continue;
            }
            boolean hasBlocks = !section.isEmpty();
            for (int y = 0; y < 16; y++) {
                int blockY = (sy << 4) + y;
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        if (hasBlocks) {
                            int emission = Math.min(
                                15,
                                section.getBlockByExtId(x, y, z)
                                    .getLightValue(world, originX + x, blockY, originZ + z));
                            if (emission > 0) {
                                int light = section.getExtBlocklightValue(x, y, z);
                                if (emission > light) {
                                    section.setExtBlocklightValue(x, y, z, emission);
                                }
                                setContribution(x, blockY, z, emission);
                                enqueue(x, blockY, z, emission);
                            }
                        }
                    }
                }
            }
        }
        for (int y = 0; y < 256; y++) {
            for (int i = 0; i < 16; i++) {
                importFace(-1, y, i, 0, i);
                importFace(16, y, i, 15, i);
                importFace(i, y, -1, i, 0);
                importFace(i, y, 16, i, 15);
            }
        }
        for (int level = 15; level > 1; level--) {
            for (int i = 0; i < sizes[level]; i++) {
                int position = queues[level][i];
                int x = (position & 63) - 16;
                int z = ((position >> 6) & 63) - 16;
                int y = position >> 12;
                if (contributionAt(x, y, z) != level) {
                    continue;
                }
                spread(x - 1, y, z, level);
                spread(x + 1, y, z, level);
                spread(x, y - 1, z, level);
                spread(x, y + 1, z, level);
                spread(x, y, z - 1, level);
                spread(x, y, z + 1, level);
            }
        }
        // Packet handlers invalidate the received chunk plus its one-block
        // border, which covers the same 3x3 renderer columns as this light wave.
        // Finish before those meshes compile; no additional redraw is needed.
    }

    private void importFace(int x, int y, int z, int targetX, int targetZ) {
        int light = lightAt(x, y, z);
        if (light > contributionAt(targetX, y, targetZ) + 1 && hasSource(x, y, z, light)) {
            spread(targetX, y, targetZ, light);
        }
    }

    // Imported light must lead uphill to an actual emitter. Otherwise a later
    // packet removing a border source can re-import our old light from a neighbour.
    // Levels strictly increase, so recursion is bounded by the fifteen light levels.
    private boolean hasSource(int x, int y, int z, int light) {
        int position = (y << 12) | ((z + 16) << 6) | (x + 16);
        Boolean cached = supported.get(position);
        if (cached != null) {
            return cached;
        }
        Chunk owner = neighbourAt(x, y, z);
        if (owner == null) {
            return false;
        }
        Block block = owner.getBlock(x & 15, y, z & 15);
        int emission = block.getLightValue(world, originX + x, y, originZ + z);
        boolean result = emission >= light;
        if (!result) {
            int opacity = Math.max(1, block.getLightOpacity(world, originX + x, y, originZ + z));
            if (opacity >= 15 && emission > 0) {
                opacity = 1;
            }
            int required = light + opacity;
            result = supports(x - 1, y, z, required) || supports(x + 1, y, z, required)
                || supports(x, y - 1, z, required)
                || supports(x, y + 1, z, required)
                || supports(x, y, z - 1, required)
                || supports(x, y, z + 1, required);
        }
        supported.put(position, result);
        return result;
    }

    private boolean supports(int x, int y, int z, int required) {
        int light = lightAt(x, y, z);
        return light >= required && hasSource(x, y, z, light);
    }

    private Chunk neighbourAt(int x, int y, int z) {
        if (x < -16 || x >= 32 || z < -16 || z >= 32 || y < 0 || y >= 256) {
            return null;
        }
        return neighbours[((z + 16) >> 4) * 3 + ((x + 16) >> 4)];
    }

    private int lightAt(int x, int y, int z) {
        Chunk owner = neighbourAt(x, y, z);
        if (owner == null) {
            return 0;
        }
        ExtendedBlockStorage section = owner.getBlockStorageArray()[y >> 4];
        return section == null ? 0 : section.getExtBlocklightValue(x & 15, y & 15, z & 15);
    }

    private void spread(int x, int y, int z, int sourceLight) {
        Chunk owner = neighbourAt(x, y, z);
        if (owner == null) {
            return;
        }
        ExtendedBlockStorage[] sections = owner.getBlockStorageArray();
        ExtendedBlockStorage section = sections[y >> 4];
        int current = section == null ? 0 : section.getExtBlocklightValue(x & 15, y & 15, z & 15);
        int contribution = contributionAt(x, y, z);
        if (contribution >= sourceLight - 1) {
            return;
        }
        int opacity = 1;
        if (section != null) {
            Block block = section.getBlockByExtId(x & 15, y & 15, z & 15);
            opacity = block.getLightOpacity(world, originX + x, y, originZ + z);
            if (opacity >= 15 && block.getLightValue(world, originX + x, y, originZ + z) > 0) {
                opacity = 1;
            }
            opacity = Math.max(1, opacity);
        }
        int light = sourceLight - opacity;
        if (light <= contribution) {
            return;
        }
        if (section == null) {
            boolean hasSky = !world.provider.hasNoSky;
            section = new ExtendedBlockStorage(y & ~15, hasSky);
            if (hasSky) {
                // Missing sections derive their saved skylight from the heightmap.
                // Preserve those values when materialising block-light storage;
                // regenerating the chunk's skylight would overwrite packet data.
                for (int localY = 0; localY < 16; localY++) {
                    for (int localZ = 0; localZ < 16; localZ++) {
                        for (int localX = 0; localX < 16; localX++) {
                            if (owner.canBlockSeeTheSky(localX, (y & ~15) + localY, localZ)) {
                                section.setExtSkylightValue(localX, localY, localZ, 15);
                            }
                        }
                    }
                }
            }
            sections[y >> 4] = section;
        }
        if (light > current) {
            section.setExtBlocklightValue(x & 15, y & 15, z & 15, light);
        }
        setContribution(x, y, z, light);
        enqueue(x, y, z, light);
    }

    private int contributionAt(int x, int y, int z) {
        byte[] section = propagated[((z + 16) >> 4) * 3 + ((x + 16) >> 4)][y >> 4];
        return section == null ? 0 : section[((y & 15) << 8) | ((z & 15) << 4) | (x & 15)];
    }

    private void setContribution(int x, int y, int z, int light) {
        byte[][] column = propagated[((z + 16) >> 4) * 3 + ((x + 16) >> 4)];
        byte[] section = column[y >> 4];
        if (section == null) {
            section = column[y >> 4] = new byte[4096];
        }
        section[((y & 15) << 8) | ((z & 15) << 4) | (x & 15)] = (byte) light;
    }

    private void enqueue(int x, int y, int z, int light) {
        if (light <= 1) {
            return;
        }
        int[] queue = queues[light];
        int size = sizes[light];
        if (queue == null) {
            queues[light] = queue = new int[256];
        } else if (size == queue.length) {
            queues[light] = queue = Arrays.copyOf(queue, size * 2);
        }
        queue[size] = (y << 12) | ((z + 16) << 6) | (x + 16);
        sizes[light] = size + 1;
    }
}
