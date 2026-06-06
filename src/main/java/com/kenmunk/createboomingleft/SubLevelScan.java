package com.kenmunk.createboomingleft;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * Incremental block-inventory scan for a single ServerSubLevel.
 * Resumes each tick from where it left off; call {@link #advance} at the
 * desired rate (e.g. 10 blocks/tick) until {@link #isComplete} returns true.
 *
 * Positions are recorded at scan-start in server-level coordinates.
 * Because Sable stores sublevel blocks at fixed world-space plot coordinates
 * rather than moving them with the visual transform, the captured min-corner
 * remains valid for the life of the scan.
 */
public class SubLevelScan {

    private final int minX, minY, minZ;
    private final int sizeY, sizeZ;
    private final int totalVolume;
    private int cursor;
    private boolean complete;
    private final EnumMap<CoreBlockType, Integer> counts = new EnumMap<>(CoreBlockType.class);
    private final ArrayList<BlockPos> explosiveSink;

    SubLevelScan(final int minX, final int minY, final int minZ,
                 final int maxX, final int maxY, final int maxZ,
                 final ArrayList<BlockPos> explosiveSink) {
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.sizeY = maxY - minY + 1;
        this.sizeZ = maxZ - minZ + 1;
        this.explosiveSink = explosiveSink;
        final int sizeX = maxX - minX + 1;
        this.totalVolume = sizeX * sizeY * sizeZ;
        this.complete = (totalVolume <= 0);
        for (final CoreBlockType type : CoreBlockType.values()) {
            counts.put(type, 0);
        }
    }

    /**
     * Advances the scan by up to {@code steps} blocks.
     *
     * @return true if the scan is now complete
     */
    boolean advance(final ServerLevel level, final int steps) {
        if (complete) return true;
        final int end = Math.min(cursor + steps, totalVolume);
        while (cursor < end) {
            final BlockPos pos = posAt(cursor);
            final var state = level.getBlockState(pos);
            if (!state.isAir()) {
                for (final CoreBlockType type : CoreBlockType.values()) {
                    if (type.matches(state, pos, level)) {
                        counts.merge(type, 1, Integer::sum);
                        if (type == CoreBlockType.EXPLOSIVE) {
                            explosiveSink.add(pos);
                        }
                    }
                }
            }
            cursor++;
        }
        if (cursor >= totalVolume) complete = true;
        return complete;
    }

    // x-major, y-middle, z-minor ordering (matches BlockPos.betweenClosed)
    private BlockPos posAt(final int index) {
        return new BlockPos(
            minX + index / (sizeY * sizeZ),
            minY + (index / sizeZ) % sizeY,
            minZ + index % sizeZ
        );
    }

    public boolean isComplete()  { return complete; }
    public int getProgress()     { return cursor; }
    public int getTotalVolume()  { return totalVolume; }

    public int getCount(final CoreBlockType type) {
        return counts.getOrDefault(type, 0);
    }

    /** Read-only view of all per-type counts. */
    public Map<CoreBlockType, Integer> getCounts() {
        return Collections.unmodifiableMap(counts);
    }
}
