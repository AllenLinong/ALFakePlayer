package io.github.dummyplayers;

import org.bukkit.Bukkit;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.event.Event;
import org.bukkit.event.EventException;
import org.bukkit.event.HandlerList;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.plugin.RegisteredListener;

final class CraftEngineCompat {
    private static final String DEATH_LISTENER = "net.momirealms.craftengine.bukkit.loot.source.EntityDeathLootListener";

    static void prepareDeath() {
        var craftEngine = Bukkit.getPluginManager().getPlugin("CraftEngine");
        if (craftEngine == null || !craftEngine.isEnabled() || !craftEngine.getPluginMeta().getVersion().equals("26.9.2")) return;
        HandlerList handlers = PlayerDeathEvent.getHandlerList();
        synchronized (handlers) {
            for (RegisteredListener hook : handlers.getRegisteredListeners()) {
                if (hook instanceof GuardedDeathListener || hook.getPlugin() != craftEngine
                    || !hook.getListener().getClass().getName().equals(DEATH_LISTENER)) continue;
                handlers.unregister(hook);
                handlers.register(new GuardedDeathListener(hook));
            }
        }
    }

    static void restore() {
        HandlerList handlers = PlayerDeathEvent.getHandlerList();
        synchronized (handlers) {
            for (RegisteredListener hook : handlers.getRegisteredListeners()) {
                if (!(hook instanceof GuardedDeathListener guard)) continue;
                handlers.unregister(guard);
                if (guard.getPlugin().isEnabled()) handlers.register(guard.original);
            }
        }
    }

    private static final class GuardedDeathListener extends RegisteredListener {
        private final RegisteredListener original;

        GuardedDeathListener(RegisteredListener original) {
            super(original.getListener(), original.getExecutor(), original.getPriority(), original.getPlugin(), original.isIgnoringCancelled());
            this.original = original;
        }

        @Override public void callEvent(Event event) throws EventException {
            if (event instanceof PlayerDeathEvent death && death.getEntity() instanceof CraftPlayer player
                && player.getHandle() instanceof NativeDummy) return;
            original.callEvent(event);
        }
    }
}
