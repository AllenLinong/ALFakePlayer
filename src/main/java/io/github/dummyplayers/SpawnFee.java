package io.github.dummyplayers;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.lang.reflect.Method;

record SpawnFee(Object economy, Method withdraw, Method deposit, Method success, double amount) {
    static SpawnFee find(double amount) throws ReflectiveOperationException {
        var services = Bukkit.getServicesManager();
        for (Class<?> service : services.getKnownServices()) {
            if (!service.getName().equals("net.milkbowl.vault.economy.Economy")) continue;
            var registration = services.getRegistration(service);
            if (registration == null) continue;
            Method withdraw = service.getMethod("withdrawPlayer", OfflinePlayer.class, double.class);
            Method deposit = service.getMethod("depositPlayer", OfflinePlayer.class, double.class);
            Method success = withdraw.getReturnType().getMethod("transactionSuccess");
            return new SpawnFee(registration.getProvider(), withdraw, deposit, success, amount);
        }
        return null;
    }

    boolean debit(OfflinePlayer player) throws ReflectiveOperationException {
        return transaction(withdraw, player);
    }

    boolean refund(OfflinePlayer player) throws ReflectiveOperationException {
        return transaction(deposit, player);
    }

    private boolean transaction(Method method, OfflinePlayer player) throws ReflectiveOperationException {
        Object response = method.invoke(economy, player, amount);
        return response != null && Boolean.TRUE.equals(success.invoke(response));
    }
}
