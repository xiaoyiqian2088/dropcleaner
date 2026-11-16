package com.dropcleaner.data;

/**
 * 一整片受保护的区块矩形：里面的掉落物永远不被清理。
 *
 * <p>单位是区块而不是方块——清理本来就按区块进行，用方块边界只会让人搞不清
 * 到底哪些地方算被保护。</p>
 */
public record ProtectedZone(String name, String dimension,
                            int minChunkX, int minChunkZ, int maxChunkX, int maxChunkZ) {

    /** 由两个任意的角组成一个矩形。 */
    public static ProtectedZone ofChunks(String name, String dimension,
                                         int chunkX1, int chunkZ1, int chunkX2, int chunkZ2) {
        return new ProtectedZone(name, dimension,
                Math.min(chunkX1, chunkX2), Math.min(chunkZ1, chunkZ2),
                Math.max(chunkX1, chunkX2), Math.max(chunkZ1, chunkZ2));
    }

    public boolean contains(String dimensionId, int chunkX, int chunkZ) {
        return dimension.equals(dimensionId)
                && chunkX >= minChunkX && chunkX <= maxChunkX
                && chunkZ >= minChunkZ && chunkZ <= maxChunkZ;
    }

    public int chunkCount() {
        return (maxChunkX - minChunkX + 1) * (maxChunkZ - minChunkZ + 1);
    }

    public int minBlockX() {
        return minChunkX << 4;
    }

    public int minBlockZ() {
        return minChunkZ << 4;
    }

    public int maxBlockX() {
        return (maxChunkX << 4) + 15;
    }

    public int maxBlockZ() {
        return (maxChunkZ << 4) + 15;
    }
}
