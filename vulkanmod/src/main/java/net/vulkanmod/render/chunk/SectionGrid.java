package net.vulkanmod.render.chunk;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.vulkanmod.render.chunk.frustum.VFrustum;
import net.vulkanmod.render.chunk.graph.GraphDirections;
import net.vulkanmod.render.chunk.util.CircularIntList;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class SectionGrid {

    protected final Level level;
    protected int gridHeight;
    protected int gridWidth;
    public RenderSection[] sections;
    final ChunkAreaManager chunkAreaManager;

    private int prevSecX;
    private int prevSecZ;

    private final CircularIntList xList;
    private final CircularIntList zList;
    private final CircularIntList.RangeIterator xComplIterator;

    public SectionGrid(Level level, int viewDistance) {
        this.level = level;
        this.setViewDistance(viewDistance);
        this.createChunks();
        this.chunkAreaManager = new ChunkAreaManager(this.gridWidth, this.gridHeight, this.level.getMinY());

        this.prevSecX = Integer.MIN_VALUE;
        this.prevSecZ = Integer.MIN_VALUE;

        this.xList = new CircularIntList(this.gridWidth);
        this.zList = new CircularIntList(this.gridWidth);
        this.xComplIterator = this.xList.createRangeIterator();
    }

    protected void createChunks() {
        if (!Minecraft.getInstance().isSameThread()) {
            throw new IllegalStateException("createChunks called from wrong thread: " + Thread.currentThread().getName());
        } else {
            int size = this.gridWidth * this.gridHeight * this.gridWidth;
            this.sections = new RenderSection[size];

            for (int j = 0; j < this.gridWidth; ++j) {
                for (int k = 0; k < this.gridHeight; ++k) {
                    for (int l = 0; l < this.gridWidth; ++l) {
                        int i1 = this.getChunkIndex(j, k, l);
                        RenderSection renderSection = new RenderSection(i1, j * 16, k * 16, l * 16);
                        this.sections[i1] = renderSection;
                    }
                }
            }
            this.setYNeighbours();
        }
    }

    public void freeAllBuffers() {
        this.chunkAreaManager.freeAllBuffers();
    }

    private int getChunkIndex(int x, int y, int z) {
        return (z * this.gridHeight + y) * this.gridWidth + x;
    }

    protected void setViewDistance(int radius) {
        int i = radius * 2 + 1;
        this.gridWidth = i;
        this.gridHeight = this.level.getSectionsCount();
        this.gridWidth = i;
    }

    /**
     * This method has been optimized with circular lists to remove costly modulo computations
     * and to reduce section repositioning to only the necessary.
     */
    public void repositionCamera(double x, double z) {
        int secX = Mth.floor(x) >> 4;
        int secZ = Mth.floor(z) >> 4;

        this.chunkAreaManager.repositionAreas(secX, secZ);

        int dx = Mth.clamp(secX - this.prevSecX, -this.gridWidth, this.gridWidth);
        int dz = Mth.clamp(secZ - this.prevSecZ, -this.gridWidth, this.gridWidth);

        int xAbsChunkIndex = secX - this.gridWidth / 2;
        int xStart = Math.floorMod(xAbsChunkIndex, this.gridWidth); // needs positive modulo
        int zAbsChunkIndex = secZ - this.gridWidth / 2;
        int zStart = Math.floorMod(zAbsChunkIndex, this.gridWidth);

        CircularIntList xList = this.xList;
        CircularIntList zList = this.zList;
        xList.updateStartIdx(xStart);
        zList.updateStartIdx(zStart);

        CircularIntList.OwnIterator xIterator = xList.iterator();
        CircularIntList.OwnIterator zIterator = zList.iterator();

        int xRangeStart;
        int xRangeEnd;
        int xComplStart;
        int xComplEnd;
        if (dx >= 0) {
            xRangeStart = this.gridWidth - dx;
            xRangeEnd = this.gridWidth - 1;
            xComplStart = 0;
            xComplEnd = xRangeStart - 1;
        } else {
            xRangeStart = 0;
            xRangeEnd = -dx - 1;
            xComplStart = xRangeEnd;
            xComplEnd = this.gridWidth - 1;
        }

        int zRangeStart;
        int zRangeEnd;
        if (dz >= 0) {
            zRangeStart = this.gridWidth - dz;
            zRangeEnd = this.gridWidth - 1;
        } else {
            zRangeStart = 0;
            zRangeEnd = -dz - 1;
        }

        CircularIntList.RangeIterator xRangeIterator = xList.getRangeIterator(xRangeStart, xRangeEnd);
        CircularIntList.RangeIterator zRangeIterator = zList.getRangeIterator(zRangeStart, zRangeEnd);
        CircularIntList.RangeIterator xComplIterator = this.xComplIterator;
        xComplIterator.update(xComplStart, xComplEnd);

        xAbsChunkIndex = secX - (this.gridWidth >> 1) + xRangeStart;
        for (int xRelativeIndex; xRangeIterator.hasNext(); xAbsChunkIndex++) {
            xRelativeIndex = xRangeIterator.next();
            int x1 = (xAbsChunkIndex << 4);

            zIterator.restart();
            zAbsChunkIndex = secZ - (this.gridWidth >> 1);

            for (int zRelativeIndex; zIterator.hasNext(); zAbsChunkIndex++) {
                zRelativeIndex = zIterator.next();
                int z1 = (zAbsChunkIndex << 4);

                for (int yRel = 0; yRel < this.gridHeight; ++yRel) {
                    moveSection(xRelativeIndex, yRel, zRelativeIndex, x1, z1,
                                xList, zList, xRangeIterator.getCurrentIndex(), zIterator.getCurrentIndex());
                }
            }
        }

        xAbsChunkIndex = secX - (this.gridWidth >> 1) + xComplStart;
        for (int xRelativeIndex; xComplIterator.hasNext(); xAbsChunkIndex++) {
            xRelativeIndex = xComplIterator.next();
            int x1 = (xAbsChunkIndex << 4);

            zRangeIterator.restart();
            zAbsChunkIndex = secZ - (this.gridWidth >> 1) + zRangeStart;

            for (int zRelativeIndex; zRangeIterator.hasNext(); zAbsChunkIndex++) {
                zRelativeIndex = zRangeIterator.next();
                int z1 = (zAbsChunkIndex << 4);

                for (int yRel = 0; yRel < this.gridHeight; ++yRel) {
                    moveSection(xRelativeIndex, yRel, zRelativeIndex, x1, z1,
                                xList, zList, xComplIterator.getCurrentIndex(), zRangeIterator.getCurrentIndex());
                }
            }
        }

        this.prevSecX = secX;
        this.prevSecZ = secZ;
    }

    private void moveSection(int xRelativeIndex, int yRel, int zRelativeIndex,
                             int x1, int z1,
                             CircularIntList xList, CircularIntList zList,
                             int xCurrentIdx, int zCurrentIdx) {

        int y1 = this.level.getMinY() + (yRel << 4);
        RenderSection renderSection = this.sections[this.getChunkIndex(xRelativeIndex, yRel, zRelativeIndex)];

        this.unsetNeighbours(renderSection);

        renderSection.setOrigin(x1, y1, z1);

        this.setNeighbours(renderSection, xList, zList, xCurrentIdx, zCurrentIdx,
                           xRelativeIndex, yRel, zRelativeIndex);

        ChunkArea oldArea = renderSection.getChunkArea();

        if (oldArea != null) {
            oldArea.removeSection();
        }

        ChunkArea chunkArea = this.chunkAreaManager.getChunkArea(renderSection, x1, y1, z1);
        chunkArea.addSection();
        renderSection.setChunkArea(chunkArea);

        renderSection.inAreaIndex = (short) (((x1 - chunkArea.position.x()) >> 4) +
                (((z1 - chunkArea.position.z()) >> 4) * 8 + ((y1 - chunkArea.position.y()) >> 4)) * 8);

    }

    private void setNeighbours(RenderSection section, CircularIntList xList, CircularIntList zList,
                               int xIdx, int zIdx, int x, int y, int z) {


        int eastX = xList.getNext(xIdx);
        int westX = xList.getPrevious(xIdx);
        int northZ = zList.getPrevious(zIdx);
        int southZ = zList.getNext(zIdx);

        if (eastX != -1) {
            RenderSection neighbour = this.sections[getChunkIndex(eastX, y, z)];
            section.setAdjacent(neighbour, GraphDirections.EAST);
        }

        if (westX != -1) {
            RenderSection neighbour = this.sections[getChunkIndex(westX, y, z)];
            section.setAdjacent(neighbour, GraphDirections.WEST);
        }

        if (northZ != -1) {
            RenderSection neighbour = this.sections[getChunkIndex(x, y, northZ)];
            section.setAdjacent(neighbour, GraphDirections.NORTH);
        }

        if (southZ != -1) {
            RenderSection neighbour = this.sections[getChunkIndex(x, y, southZ)];
            section.setAdjacent(neighbour, GraphDirections.SOUTH);
        }
    }

    private void unsetNeighbours(RenderSection section) {
        //Reset only X-Z directions
        section.adjDirs &= 0b11;

        for (int i = 2; i < 6; i++) {
            section.resetAdjacent(i);
        }
    }

    private void setYNeighbours() {
        for (int j = 0; j < this.gridWidth; ++j) {
            for (int k = 0; k < this.gridHeight; ++k) {
                for (int l = 0; l < this.gridWidth; ++l) {
                    int i1 = this.getChunkIndex(j, k, l);
                    this.setYNeighbours(this.sections[i1], j, k, l);
                }
            }
        }
    }

    private void setYNeighbours(RenderSection section, int x, int y, int z) {
        if (y != this.gridHeight - 1) {
            RenderSection neighbour = this.sections[getChunkIndex(x, y + 1, z)];
            section.setAdjacent(neighbour, GraphDirections.UP);
        }

        if (y != 0) {
            RenderSection neighbour = this.sections[getChunkIndex(x, y - 1, z)];
            section.setAdjacent(neighbour, GraphDirections.DOWN);
        }
    }

    private void setChunkArea(RenderSection section, int x, int y, int z) {
        ChunkArea oldArea = section.getChunkArea();

        if (oldArea != null) {
            oldArea.removeSection();
        }

        ChunkArea chunkArea = this.chunkAreaManager.getChunkArea(section, x, y, z);
        chunkArea.addSection();
        section.setChunkArea(chunkArea);
    }

    public void setDirty(int sectionX, int sectionY, int sectionZ, boolean playerChanged) {
        int i = Math.floorMod(sectionX, this.gridWidth);
        int j = Math.floorMod(sectionY - this.level.getMinSectionY(), this.gridHeight);
        int k = Math.floorMod(sectionZ, this.gridWidth);
        RenderSection renderSection = this.sections[this.getChunkIndex(i, j, k)];
        renderSection.setDirty(playerChanged);
    }

    @Nullable
    public RenderSection getSectionAtBlockPos(BlockPos blockPos) {
        return this.getSectionAtBlockPos(blockPos.getX(), blockPos.getY(), blockPos.getZ());
    }

    public RenderSection getSectionAtBlockPos(int x, int y, int z) {
        int i = x >> 4;
        int j = (y - this.level.getMinY()) >> 4;
        int k = z >> 4;

        return this.getSectionAtSectionPos(i, j, k);
    }

    public RenderSection getSectionAtSectionPos(int i, int j, int k) {
        if (j >= 0 && j < this.gridHeight) {
            i = Math.floorMod(i, this.gridWidth);
            k = Math.floorMod(k, this.gridWidth);
            return this.sections[this.getChunkIndex(i, j, k)];
        } else {
            return null;
        }
    }

    public List<RenderSection> getRenderSectionsAt(int x, int z) {
        ObjectArrayList<RenderSection> list = new ObjectArrayList<>(24);

        int i = Math.floorMod(x, this.gridWidth);
        int k = Math.floorMod(z, this.gridWidth);

        for (int y1 = 0; y1 < gridHeight; ++y1) {
            list.add(this.sections[this.getChunkIndex(i, y1, k)]);
        }

        return list;
    }

    public void updateFrustumVisibility(VFrustum frustum) {
        this.chunkAreaManager.updateFrustumVisibility(frustum);
    }

    public ChunkAreaManager getChunkAreaManager() {
        return chunkAreaManager;
    }

    public int getSectionCount() {
        return this.sections.length;
    }
}
