package com.boydti.fawe.example;

import com.boydti.fawe.Fawe;
import com.boydti.fawe.FaweCache;
import com.boydti.fawe.config.Settings;
import com.boydti.fawe.object.FaweChunk;
import com.boydti.fawe.object.FaweQueue;
import com.boydti.fawe.object.IntegerPair;
import com.boydti.fawe.object.RunnableVal;
import com.boydti.fawe.object.exception.FaweException;
import com.boydti.fawe.util.MainUtil;
import com.boydti.fawe.util.MathMan;
import com.boydti.fawe.util.SetQueue;
import com.boydti.fawe.util.TaskManager;
import com.sk89q.jnbt.CompoundTag;
import com.sk89q.worldedit.blocks.BlockMaterial;
import com.sk89q.worldedit.world.biome.BaseBiome;
import com.sk89q.worldedit.world.registry.BundledBlockData;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

public abstract class MappedFaweQueue<WORLD, CHUNK, SECTION> extends FaweQueue {

    private WORLD impWorld;

    /**
     * Map of chunks in the queue
     */
    public ConcurrentHashMap<Long, FaweChunk> blocks = new ConcurrentHashMap<>();
    public ConcurrentLinkedDeque<FaweChunk> chunks = new ConcurrentLinkedDeque<FaweChunk>() {
        @Override
        public boolean add(FaweChunk o) {
            if (getProgressTask() != null) {
                getProgressTask().run(ProgressType.QUEUE, size() + 1);
            }
            return super.add(o);
        }
    };
    public ArrayDeque<Runnable> tasks = new ArrayDeque<>();

    @Override
    public Collection<FaweChunk> getFaweChunks() {
        return Collections.unmodifiableCollection(chunks);
    }

    @Override
    public void optimize() {
        ArrayList<Thread> threads = new ArrayList<Thread>();
        for (final FaweChunk chunk : chunks) {
            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    chunk.optimize();
                }
            });
            threads.add(thread);
            thread.start();
        }
        for (Thread thread : threads) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                MainUtil.handleError(e);
            }
        }
    }

    @Override
    public void addNotifyTask(Runnable runnable) {
        this.tasks.add(runnable);
        size();
    }

    public MappedFaweQueue(final String world) {
        super(world);
    }

    public abstract WORLD getImpWorld();

    public abstract boolean isChunkLoaded(WORLD world, int x, int z);

    public abstract boolean regenerateChunk(WORLD world, int x, int z);

    public abstract boolean setComponents(FaweChunk fc, RunnableVal<FaweChunk> changeTask);

    @Override
    public abstract FaweChunk getFaweChunk(int x, int z);

    public abstract boolean loadChunk(WORLD world, int x, int z, boolean generate);

    public abstract CHUNK getCachedSections(WORLD world, int cx, int cz);

    @Override
    public boolean isChunkLoaded(int x, int z) {
        return isChunkLoaded(getWorld(), x, z);
    };

    public WORLD getWorld() {
        if (impWorld != null) {
            return impWorld;
        }
        return impWorld = getImpWorld();
    }

    @Override
    public boolean regenerateChunk(int x, int z) {
        return regenerateChunk(getWorld(), x, z);
    }

    @Override
    public void addNotifyTask(int x, int z, Runnable runnable) {
        long pair = (long) (x) << 32 | (z) & 0xFFFFFFFFL;
        FaweChunk result = this.blocks.get(pair);
        if (result == null) {
            result = this.getFaweChunk(x, z);
            result.addNotifyTask(runnable);
            FaweChunk previous = this.blocks.put(pair, result);
            if (previous == null) {
                chunks.add(result);
                return;
            }
            this.blocks.put(pair, previous);
            result = previous;
        }
        result.addNotifyTask(runnable);
    }



    private FaweChunk lastWrappedChunk;
    private int lastX = Integer.MIN_VALUE;
    private int lastZ = Integer.MIN_VALUE;

    @Override
    public boolean setBlock(int x, int y, int z, int id, int data) {
        int cx = x >> 4;
        int cz = z >> 4;
        if (cx != lastX || cz != lastZ) {
            lastX = cx;
            lastZ = cz;
            long pair = (long) (cx) << 32 | (cz) & 0xFFFFFFFFL;
            lastWrappedChunk = this.blocks.get(pair);
            if (lastWrappedChunk == null) {
                lastWrappedChunk = this.getFaweChunk(cx, cz);
                lastWrappedChunk.setBlock(x & 15, y, z & 15, id, data);
                FaweChunk previous = this.blocks.put(pair, lastWrappedChunk);
                if (previous == null) {
                    chunks.add(lastWrappedChunk);
                    return true;
                }
                this.blocks.put(pair, previous);
                lastWrappedChunk = previous;
            }
        }
        lastWrappedChunk.setBlock(x & 15, y, z & 15, id, data);
        return true;
    }

    @Override
    public boolean setBlock(int x, int y, int z, int id) {
        int cx = x >> 4;
        int cz = z >> 4;
        if (cx != lastX || cz != lastZ) {
            lastX = cx;
            lastZ = cz;
            long pair = (long) (cx) << 32 | (cz) & 0xFFFFFFFFL;
            lastWrappedChunk = this.blocks.get(pair);
            if (lastWrappedChunk == null) {
                lastWrappedChunk = this.getFaweChunk(x >> 4, z >> 4);
                lastWrappedChunk.setBlock(x & 15, y, z & 15, id);
                FaweChunk previous = this.blocks.put(pair, lastWrappedChunk);
                if (previous == null) {
                    chunks.add(lastWrappedChunk);
                    return true;
                }
                this.blocks.put(pair, previous);
                lastWrappedChunk = previous;
            }
        }
        lastWrappedChunk.setBlock(x & 15, y, z & 15, id);
        return true;
    }

    @Override
    public void setTile(int x, int y, int z, CompoundTag tag) {
        if ((y > 255) || (y < 0)) {
            return;
        }
        int cx = x >> 4;
        int cz = z >> 4;
        if (cx != lastX || cz != lastZ) {
            lastX = cx;
            lastZ = cz;
            long pair = (long) (cx) << 32 | (cz) & 0xFFFFFFFFL;
            lastWrappedChunk = this.blocks.get(pair);
            if (lastWrappedChunk == null) {
                lastWrappedChunk = this.getFaweChunk(x >> 4, z >> 4);
                lastWrappedChunk.setTile(x & 15, y, z & 15, tag);
                FaweChunk previous = this.blocks.put(pair, lastWrappedChunk);
                if (previous == null) {
                    chunks.add(lastWrappedChunk);
                    return;
                }
                this.blocks.put(pair, previous);
                lastWrappedChunk = previous;
            }
        }
        lastWrappedChunk.setTile(x & 15, y, z & 15, tag);
    }

    @Override
    public void setEntity(int x, int y, int z, CompoundTag tag) {
        if ((y > 255) || (y < 0)) {
            return;
        }
        int cx = x >> 4;
        int cz = z >> 4;
        if (cx != lastX || cz != lastZ) {
            lastX = cx;
            lastZ = cz;
            long pair = (long) (cx) << 32 | (cz) & 0xFFFFFFFFL;
            lastWrappedChunk = this.blocks.get(pair);
            if (lastWrappedChunk == null) {
                lastWrappedChunk = this.getFaweChunk(x >> 4, z >> 4);
                lastWrappedChunk.setEntity(tag);
                FaweChunk previous = this.blocks.put(pair, lastWrappedChunk);
                if (previous == null) {
                    chunks.add(lastWrappedChunk);
                    return;
                }
                this.blocks.put(pair, previous);
                lastWrappedChunk = previous;
            }
        }
        lastWrappedChunk.setEntity(tag);
    }

    @Override
    public void removeEntity(int x, int y, int z, UUID uuid) {
        if ((y > 255) || (y < 0)) {
            return;
        }
        int cx = x >> 4;
        int cz = z >> 4;
        if (cx != lastX || cz != lastZ) {
            lastX = cx;
            lastZ = cz;
            long pair = (long) (cx) << 32 | (cz) & 0xFFFFFFFFL;
            lastWrappedChunk = this.blocks.get(pair);
            if (lastWrappedChunk == null) {
                lastWrappedChunk = this.getFaweChunk(x >> 4, z >> 4);
                lastWrappedChunk.removeEntity(uuid);
                FaweChunk previous = this.blocks.put(pair, lastWrappedChunk);
                if (previous == null) {
                    chunks.add(lastWrappedChunk);
                    return;
                }
                this.blocks.put(pair, previous);
                lastWrappedChunk = previous;
            }
        }
        lastWrappedChunk.removeEntity(uuid);
    }

    @Override
    public boolean setBiome(int x, int z, BaseBiome biome) {
        long pair = (long) (x >> 4) << 32 | (z >> 4) & 0xFFFFFFFFL;
        FaweChunk result = this.blocks.get(pair);
        if (result == null) {
            result = this.getFaweChunk(x >> 4, z >> 4);
            FaweChunk previous = this.blocks.put(pair, result);
            if (previous != null) {
                this.blocks.put(pair, previous);
                result = previous;
            } else {
                chunks.add(result);
            }
        }
        result.setBiome(x & 15, z & 15, biome);
        return true;
    }

    @Override
    public FaweChunk next() {
        lastX = Integer.MIN_VALUE;
        lastZ = Integer.MIN_VALUE;
        try {
            if (this.blocks.size() == 0) {
                return null;
            }
            synchronized (blocks) {
                FaweChunk chunk = chunks.poll();
                if (chunk != null) {
                    blocks.remove(chunk.longHash());
                    this.execute(chunk);
                    return chunk;
                }
            }
        } catch (Throwable e) {
            MainUtil.handleError(e);
        }
        return null;
    }

    public void runTasks() {
        if (getProgressTask() != null) {
            getProgressTask().run(ProgressType.DONE, 1);
        }
        ArrayDeque<Runnable> tmp = new ArrayDeque<>(tasks);
        tasks.clear();
        for (Runnable run : tmp) {
            try {
                run.run();
            } catch (Throwable e) {
                MainUtil.handleError(e);
            }
        }
    }

    @Override
    public int size() {
        if (chunks.size() == 0 && SetQueue.IMP.getStage(this) != SetQueue.QueueStage.INACTIVE) {
            runTasks();
        }
        return chunks.size();
    }

    private ConcurrentLinkedDeque<FaweChunk> toUpdate = new ConcurrentLinkedDeque<>();

    private int dispatched = 0;

    public boolean execute(final FaweChunk fc) {
        if (fc == null) {
            return false;
        }
        // Set blocks / entities / biome
        if (getProgressTask() != null) {
            getProgressTask().run(ProgressType.QUEUE, chunks.size());
            getProgressTask().run(ProgressType.DISPATCH, ++dispatched);
        }
        if (getChangeTask() != null) {
            if (!this.setComponents(fc, new RunnableVal<FaweChunk>() {
                @Override
                public void run(FaweChunk before) {
                    getChangeTask().run(before, fc);
                }
            })) {
                return false;
            }
        } else if (!this.setComponents(fc, null)) {
            return false;
        }
        fc.executeNotifyTasks();
        return true;
    }

    @Override
    public void clear() {
        this.blocks.clear();
        this.chunks.clear();
        runTasks();
    }

    @Override
    public void setChunk(FaweChunk chunk) {
        FaweChunk previous = this.blocks.put(chunk.longHash(), (FaweChunk) chunk);
        if (previous != null) {
            chunks.remove(previous);
        }
        chunks.add((FaweChunk) chunk);
    }

    public int lastChunkX = Integer.MIN_VALUE;
    public int lastChunkZ = Integer.MIN_VALUE;
    public int lastChunkY = Integer.MIN_VALUE;

    public CHUNK lastChunkSections;
    public SECTION lastSection;

    public SECTION getCachedSection(CHUNK chunk, int cy) {
        return (SECTION) lastChunkSections;
    }

    public abstract int getCombinedId4Data(SECTION section, int x, int y, int z);

    public final RunnableVal<IntegerPair> loadChunk = new RunnableVal<IntegerPair>() {
        @Override
        public void run(IntegerPair coord) {
            loadChunk(getWorld(), coord.x, coord.z, true);
        }
    };

    long average = 0;

    public boolean ensureChunkLoaded(int cx, int cz) throws FaweException.FaweChunkLoadException {
        if (!isChunkLoaded(cx, cz)) {
            boolean sync = Thread.currentThread() == Fawe.get().getMainThread();
            if (sync) {
                loadChunk(getWorld(), cx, cz, true);
            } else if (Settings.HISTORY.CHUNK_WAIT_MS > 0) {
                loadChunk.value = new IntegerPair(cx, cz);
                TaskManager.IMP.syncWhenFree(loadChunk, Settings.HISTORY.CHUNK_WAIT_MS);
                if (!isChunkLoaded(cx, cz)) {
                    throw new FaweException.FaweChunkLoadException();
                }
            } else {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean hasBlock(int x, int y, int z) throws FaweException.FaweChunkLoadException {
        int cx = x >> 4;
        int cz = z >> 4;
        int cy = y >> 4;
        if (cx != lastChunkX || cz != lastChunkZ) {
            lastChunkX = cx;
            lastChunkZ = cz;
            if (!ensureChunkLoaded(cx, cz)) {
                return false;
            }
            lastChunkSections = getCachedSections(getWorld(), cx, cz);
            lastSection = getCachedSection(lastChunkSections, cy);
        } else if (cy != lastChunkY) {
            if (lastChunkSections == null) {
                return false;
            }
            lastSection = getCachedSection(lastChunkSections, cy);
        }

        if (lastSection == null) {
            return false;
        }
        return hasBlock(lastSection, x, y, z);
    }

    public boolean hasBlock(SECTION section, int x, int y, int z) {
        return getCombinedId4Data(lastSection, x, y, z) != 0;
    }

    public int getOpacity(SECTION section, int x, int y, int z) {
        int combined = getCombinedId4Data(section, x, y, z);
        if (combined == 0) {
            return 0;
        }
        BlockMaterial block = BundledBlockData.getInstance().getMaterialById(FaweCache.getId(combined));
        if (block == null) {
            return 15;
        }
        return Math.min(15, block.getLightOpacity());
    }

    public int getBrightness(SECTION section, int x, int y, int z) {
        int combined = getCombinedId4Data(section, x, y, z);
        if (combined == 0) {
            return 0;
        }
        BlockMaterial block = BundledBlockData.getInstance().getMaterialById(FaweCache.getId(combined));
        if (block == null) {
            return 15;
        }
        return Math.min(15, block.getLightValue());
    }

    public int getOpacityBrightnessPair(SECTION section, int x, int y, int z) {
        return MathMan.pair16(Math.min(15, getOpacity(section, x, y, z)), getBrightness(section, x, y, z));
    }

    public abstract int getSkyLight(SECTION sections, int x, int y, int z);

    public abstract int getEmmittedLight(SECTION sections, int x, int y, int z);

    public int getLight(SECTION sections, int x, int y, int z) {
        if (!hasSky()) {
            return getEmmittedLight(sections, x, y, z);
        }
        return Math.max(getSkyLight(sections, x, y, z), getEmmittedLight(sections, x, y, z));
    }

    @Override
    public int getLight(int x, int y, int z) {
        int cx = x >> 4;
        int cz = z >> 4;
        int cy = y >> 4;
        if (cx != lastChunkX || cz != lastChunkZ) {
            lastChunkX = cx;
            lastChunkZ = cz;
            if (!ensureChunkLoaded(cx, cz)) {
                return 0;
            }
            lastChunkSections = getCachedSections(getWorld(), cx, cz);
            lastSection = getCachedSection(lastChunkSections, cy);
        } else if (cy != lastChunkY) {
            if (lastChunkSections == null) {
                return 0;
            }
            lastSection = getCachedSection(lastChunkSections, cy);
        }

        if (lastSection == null) {
            return 0;
        }
        return getLight(lastSection, x, y, z);
    }

    @Override
    public int getSkyLight(int x, int y, int z) {
        int cx = x >> 4;
        int cz = z >> 4;
        int cy = y >> 4;
        if (cx != lastChunkX || cz != lastChunkZ) {
            lastChunkX = cx;
            lastChunkZ = cz;
            if (!ensureChunkLoaded(cx, cz)) {
                return 0;
            }
            lastChunkSections = getCachedSections(getWorld(), cx, cz);
            lastSection = getCachedSection(lastChunkSections, cy);
        } else if (cy != lastChunkY) {
            if (lastChunkSections == null) {
                return 0;
            }
            lastSection = getCachedSection(lastChunkSections, cy);
        }
        if (lastSection == null) {
            return 0;
        }
        return getSkyLight(lastSection, x, y, z);
    }

    @Override
    public int getEmmittedLight(int x, int y, int z) {
        int cx = x >> 4;
        int cz = z >> 4;
        int cy = y >> 4;
        if (cx != lastChunkX || cz != lastChunkZ) {
            lastChunkX = cx;
            lastChunkZ = cz;
            if (!ensureChunkLoaded(cx, cz)) {
                return 0;
            }
            lastChunkSections = getCachedSections(getWorld(), cx, cz);
            lastSection = getCachedSection(lastChunkSections, cy);
        } else if (cy != lastChunkY) {
            if (lastChunkSections == null) {
                return 0;
            }
            lastSection = getCachedSection(lastChunkSections, cy);
        }
        if (lastSection == null) {
            return 0;
        }
        return getEmmittedLight(lastSection, x, y, z);
    }

    @Override
    public int getOpacity(int x, int y, int z) {
        int cx = x >> 4;
        int cz = z >> 4;
        int cy = y >> 4;
        if (cx != lastChunkX || cz != lastChunkZ) {
            lastChunkX = cx;
            lastChunkZ = cz;
            if (!ensureChunkLoaded(cx, cz)) {
                return 0;
            }
            lastChunkSections = getCachedSections(getWorld(), cx, cz);
            lastSection = getCachedSection(lastChunkSections, cy);
        } else if (cy != lastChunkY) {
            if (lastChunkSections == null) {
                return 0;
            }
            lastSection = getCachedSection(lastChunkSections, cy);
        }

        if (lastSection == null) {
            return 0;
        }
        return getOpacity(lastSection, x, y, z);
    }

    @Override
    public int getOpacityBrightnessPair(int x, int y, int z) {
        int cx = x >> 4;
        int cz = z >> 4;
        int cy = y >> 4;
        if (cx != lastChunkX || cz != lastChunkZ) {
            lastChunkX = cx;
            lastChunkZ = cz;
            if (!ensureChunkLoaded(cx, cz)) {
                return 0;
            }
            lastChunkSections = getCachedSections(getWorld(), cx, cz);
            lastSection = getCachedSection(lastChunkSections, cy);
        } else if (cy != lastChunkY) {
            if (lastChunkSections == null) {
                return 0;
            }
            lastSection = getCachedSection(lastChunkSections, cy);
        }

        if (lastSection == null) {
            return 0;
        }
        return getOpacityBrightnessPair(lastSection, x, y, z);
    }

    @Override
    public int getBrightness(int x, int y, int z) {
        int cx = x >> 4;
        int cz = z >> 4;
        int cy = y >> 4;
        if (cx != lastChunkX || cz != lastChunkZ) {
            lastChunkX = cx;
            lastChunkZ = cz;
            if (!ensureChunkLoaded(cx, cz)) {
                return 0;
            }
            lastChunkSections = getCachedSections(getWorld(), cx, cz);
            lastSection = getCachedSection(lastChunkSections, cy);
        } else if (cy != lastChunkY) {
            if (lastChunkSections == null) {
                return 0;
            }
            lastSection = getCachedSection(lastChunkSections, cy);
        }

        if (lastSection == null) {
            return 0;
        }
        return getBrightness(lastSection, x, y, z);
    }

    @Override
    public int getCombinedId4Data(int x, int y, int z) throws FaweException.FaweChunkLoadException {
        int cx = x >> 4;
        int cz = z >> 4;
        int cy = y >> 4;
        if (cx != lastChunkX || cz != lastChunkZ) {
            lastChunkX = cx;
            lastChunkZ = cz;
            if (!ensureChunkLoaded(cx, cz)) {
                return 0;
            }
            lastChunkSections = getCachedSections(getWorld(), cx, cz);
            lastSection = getCachedSection(lastChunkSections, cy);
        } else if (cy != lastChunkY) {
            if (lastChunkSections == null) {
                return 0;
            }
            lastSection = getCachedSection(lastChunkSections, cy);
        }

        if (lastSection == null) {
            return 0;
        }
        return getCombinedId4Data(lastSection, x, y, z);
    }
}
