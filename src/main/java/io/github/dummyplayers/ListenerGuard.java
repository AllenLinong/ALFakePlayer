package io.github.dummyplayers;

import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventException;
import org.bukkit.event.HandlerList;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * 假人挖掘、放置、种植、钓鱼、进食时会触发和真人一样的事件。假人不经过进服流程，不触发 PlayerJoinEvent，
 * 有的插件在进服事件里才准备玩家的数据，处理假人的事件时就会报错（例如 GuibindPro 的空指针），每挖一下刷一次屏。
 *
 * <p>这里把其他插件对这些事件的监听包一层：处理假人的事件时抛出的异常不再交给服务器打印，每个插件只提示一次；
 * 事件照常传给它和其他插件，真人的事件完全不受影响。</p>
 */
final class ListenerGuard {
    private static final List<HandlerList> EVENTS = List.of(PlayerInteractEvent.getHandlerList(), BlockBreakEvent.getHandlerList(),
        BlockPlaceEvent.getHandlerList(), PlayerFishEvent.getHandlerList(), PlayerItemConsumeEvent.getHandlerList());
    private static final Set<String> warned = ConcurrentHashMap.newKeySet();

    private ListenerGuard() { }

    /** 包住其他插件的监听；已经包过的跳过。插件启用后、其他插件启用时各调用一次。顺序保持不变。 */
    static void install(Plugin self, Logger logger) {
        for (HandlerList handlers : EVENTS) {
            synchronized (handlers) {
                RegisteredListener[] hooks = handlers.getRegisteredListeners();
                boolean needed = false;
                for (RegisteredListener hook : hooks) needed |= !(hook instanceof Guarded) && hook.getPlugin() != self;
                if (!needed) continue;
                // 全部取下再按原顺序放回，同一优先级内的先后不变
                for (RegisteredListener hook : hooks) handlers.unregister(hook);
                for (RegisteredListener hook : hooks) {
                    handlers.register(hook instanceof Guarded || hook.getPlugin() == self ? hook : new Guarded(hook, logger));
                }
            }
        }
    }

    static void restore() {
        for (HandlerList handlers : EVENTS) {
            synchronized (handlers) {
                RegisteredListener[] hooks = handlers.getRegisteredListeners();
                boolean guarded = false;
                for (RegisteredListener hook : hooks) guarded |= hook instanceof Guarded;
                if (!guarded) continue;
                for (RegisteredListener hook : hooks) handlers.unregister(hook);
                for (RegisteredListener hook : hooks) {
                    RegisteredListener original = hook instanceof Guarded guard ? guard.original : hook;
                    if (original.getPlugin().isEnabled()) handlers.register(original);
                }
            }
        }
    }

    private static Player player(Event event) {
        return switch (event) {
            case PlayerEvent player -> player.getPlayer();
            case BlockBreakEvent breaking -> breaking.getPlayer();
            case BlockPlaceEvent placing -> placing.getPlayer();
            default -> null;
        };
    }

    private static final class Guarded extends RegisteredListener {
        private final RegisteredListener original;
        private final Logger logger;

        Guarded(RegisteredListener original, Logger logger) {
            super(original.getListener(), original.getExecutor(), original.getPriority(), original.getPlugin(), original.isIgnoringCancelled());
            this.original = original;
            this.logger = logger;
        }

        @Override public void callEvent(Event event) throws EventException {
            try {
                original.callEvent(event);
            } catch (EventException | RuntimeException error) {
                if (!(player(event) instanceof CraftPlayer player) || !(player.getHandle() instanceof NativeDummy)) throw error;
                String plugin = getPlugin().getName();
                if (warned.add(plugin + " " + event.getEventName())) {
                    Throwable cause = error.getCause() != null ? error.getCause() : error;
                    logger.warning(plugin + " failed to handle " + event.getEventName() + " of a fake player (" + cause
                        + "), probably because fake players never fire PlayerJoinEvent. Its errors for fake players are no longer logged.");
                }
            }
        }
    }
}
