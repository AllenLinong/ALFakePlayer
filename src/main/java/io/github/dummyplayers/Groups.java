package io.github.dummyplayers;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.node.types.InheritanceNode;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

interface Groups {
    CompletableFuture<Void> prepare(Roster.Entry entry, String group);
    CompletableFuture<Boolean> exists(String group);
    String prefix(Roster.Entry entry);
    /** 权限组自身的前缀，用于 /afp test 的测试条目；组不存在或没有前缀时为空字符串。 */
    String groupPrefix(String group);
    /**
     * TAB 测试假人通过服务器聊天流程说话时，聊天插件按 LuckPerms 读取它的前缀：确保这个 UUID 有 LuckPerms 用户、
     * 只继承 group 这一个组，并加载在内存里。
     */
    CompletableFuture<Void> prepareUser(java.util.UUID id, String name, String group);
    /** 已加载的 LuckPerms 用户对这个权限的设置；没有加载、没有设置或没装 LuckPerms 时为 null。 */
    Boolean permission(java.util.UUID id, String node);

    Groups NONE = new Groups() {
        public CompletableFuture<Void> prepare(Roster.Entry entry, String group) { return CompletableFuture.completedFuture(null); }
        public CompletableFuture<Boolean> exists(String group) { return CompletableFuture.completedFuture(true); }
        public String prefix(Roster.Entry entry) { return ""; }
        public String groupPrefix(String group) { return ""; }
        public CompletableFuture<Void> prepareUser(java.util.UUID id, String name, String group) { return CompletableFuture.completedFuture(null); }
        public Boolean permission(java.util.UUID id, String node) { return null; }
    };

    final class Luck implements Groups {
        private final LuckPerms api = LuckPermsProvider.get();

        /** LuckPerms 组名统一为小写；配置里写成大写也能匹配。 */
        private static String normalize(String group) { return group.trim().toLowerCase(Locale.ROOT); }

        static String missing(String group) {
            return "LuckPerms group '" + group + "' does not exist. Create it with /lp creategroup " + group
                + " or change luckperms.group in config.yml.";
        }

        /** loadGroup 会在内存里没有时去存储读取，跨服共用数据库、其他子服新建的组也能找到。 */
        public CompletableFuture<Boolean> exists(String group) {
            return api.getGroupManager().loadGroup(normalize(group)).thenApply(found -> found.isPresent());
        }

        public CompletableFuture<Void> prepare(Roster.Entry entry, String configured) {
            String group = normalize(configured);
            return exists(group).thenCompose(found -> {
                if (!found) return CompletableFuture.failedFuture(new IllegalArgumentException(missing(group)));
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
            });
        }

        public CompletableFuture<Void> prepareUser(java.util.UUID id, String name, String configured) {
            String group = normalize(configured);
            return api.getUserManager().loadUser(id, name).thenCompose(user -> {
                var parents = user.getNodes(NodeType.INHERITANCE);
                boolean correct = parents.size() == 1 && parents.stream().allMatch(node -> node.getGroupName().equals(group)
                    && node.getValue() && !node.hasExpiry() && node.getContexts().isEmpty());
                if (correct && user.getPrimaryGroup().equals(group)) return CompletableFuture.completedFuture(null);
                user.data().clear(NodeType.INHERITANCE::matches);
                user.data().add(InheritanceNode.builder(group).build());
                user.setPrimaryGroup(group);
                return api.getUserManager().saveUser(user);
            });
        }

        public Boolean permission(java.util.UUID id, String node) {
            var user = api.getUserManager().getUser(id);
            if (user == null) return null;
            return switch (user.getCachedData().getPermissionData().checkPermission(node)) {
                case TRUE -> true;
                case FALSE -> false;
                default -> null;
            };
        }

        public String groupPrefix(String configured) {
            var group = api.getGroupManager().getGroup(normalize(configured));
            String prefix = group == null ? null : group.getCachedData().getMetaData().getPrefix();
            return prefix == null ? "" : prefix;
        }

        public String prefix(Roster.Entry entry) {
            var user = api.getUserManager().getUser(entry.id);
            if (user == null) return "";
            String prefix = user.getCachedData().getMetaData().getPrefix();
            return prefix == null ? "" : prefix;
        }
    }
}
