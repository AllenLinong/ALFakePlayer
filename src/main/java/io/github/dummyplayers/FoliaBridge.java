package io.github.dummyplayers;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.Bukkit;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

final class FoliaBridge {
    private final Method currentRegion;
    private final Method removeConnection;
    private final Field connections;

    private FoliaBridge(Method currentRegion, Method removeConnection, Field connections) {
        this.currentRegion = currentRegion;
        this.removeConnection = removeConnection;
        this.connections = connections;
    }

    static FoliaBridge find() throws ReflectiveOperationException {
        Class<?> scheduler;
        try { scheduler = Class.forName("io.papermc.paper.threadedregions.TickRegionScheduler"); }
        catch (ClassNotFoundException ignored) { return new FoliaBridge(null, null, null); }
        Method current = scheduler.getMethod("getCurrentRegionizedWorldData");
        Class<?> region = current.getReturnType();
        try { return new FoliaBridge(current, region.getMethod("removeConnection", ServerPlayer.class), null); }
        catch (NoSuchMethodException ignored) { return new FoliaBridge(current, null, region.getField("connections")); }
    }

    void detach(NativeDummy dummy) {
        if (currentRegion == null || dummy.connection == null) return;
        try {
            Object region = currentRegion.invoke(null);
            if (region == null) throw new IllegalStateException("Fake player removed outside its Folia region");
            if (removeConnection != null) removeConnection.invoke(region, dummy);
            else ((List<?>) connections.get(region)).remove(dummy.link);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Cannot detach Folia fake connection", error);
        }
    }

    boolean regionThreaded() { return currentRegion != null; }

    boolean inShutdownRegion(NativeDummy dummy, Runnable cleanup) {
        Thread thread = Thread.currentThread();
        try {
            Class<?> shutdownThread = Class.forName("io.papermc.paper.threadedregions.RegionShutdownThread");
            if (!shutdownThread.isInstance(thread)) return false;

            Field context = shutdownThread.getField("shuttingDown");
            Object previous = context.get(thread);
            Object regionizer = ServerLevel.class.getField("regioniser").get(dummy.level());
            var chunk = dummy.chunkPosition();
            Object region = regionizer.getClass().getMethod("getRegionAtSynchronised", int.class, int.class)
                .invoke(regionizer, chunk.x(), chunk.z());
            if (region == null) return false;

            context.set(thread, region);
            try {
                if (!Bukkit.isOwnedByCurrentRegion(dummy.getBukkitEntity())) return false;
                cleanup.run();
                return true;
            } finally { context.set(thread, previous); }
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Cannot enter Folia fake player shutdown region", error);
        }
    }
}
