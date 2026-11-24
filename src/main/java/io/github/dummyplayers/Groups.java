package io.github.dummyplayers;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.node.types.InheritanceNode;
import java.util.concurrent.CompletableFuture;

interface Groups {
    CompletableFuture<Void> prepare(Roster.Entry entry, String group);
    String prefix(Roster.Entry entry);

    Groups NONE = new Groups() {
        public CompletableFuture<Void> prepare(Roster.Entry entry, String group) { return CompletableFuture.completedFuture(null); }
        public String prefix(Roster.Entry entry) { return ""; }
    };

    final class Luck implements Groups {
        private final LuckPerms api = LuckPermsProvider.get();

        public CompletableFuture<Void> prepare(Roster.Entry entry, String group) {
            if (api.getGroupManager().getGroup(group) == null) {
                return CompletableFuture.failedFuture(new IllegalArgumentException("LuckPerms group not loaded: " + group));
            }
            return api.getUserManager().loadUser(entry.id).thenCompose(user -> {
                var parents = user.getNodes(NodeType.INHERITANCE);
                boolean correct = parents.stream().anyMatch(node -> node.getGroupName().equals(group) && node.getValue()
                    && !node.hasExpiry() && node.getContexts().isEmpty());
                if (parents.size() == 1 && correct && user.getPrimaryGroup().equals(group)) {
                    return CompletableFuture.completedFuture(null);
                }
                user.data().clear(NodeType.INHERITANCE::matches);
                user.data().add(InheritanceNode.builder(group).build());
                user.setPrimaryGroup(group);
                return api.getUserManager().saveUser(user);
            });
        }

        public String prefix(Roster.Entry entry) {
            var user = api.getUserManager().getUser(entry.id);
            if (user == null) return "";
            String prefix = user.getCachedData().getMetaData().getPrefix();
            return prefix == null ? "" : prefix;
        }
    }
}
