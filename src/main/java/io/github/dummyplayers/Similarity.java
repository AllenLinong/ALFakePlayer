package io.github.dummyplayers;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** 测试假人聊天用的小工具：判断两句话是不是差不多（免得重复），以及给名字起个简称。 */
final class Similarity {
    private Similarity() { }

    /** 只留下文字和数字，转小写：“笑死！！”和“笑死”一样。 */
    static String normalize(String text) {
        var result = new StringBuilder();
        text.toLowerCase(Locale.ROOT).codePoints().filter(Character::isLetterOrDigit).forEach(result::appendCodePoint);
        return result.toString();
    }

    /**
     * 两句话差不多：去掉标点后相同；或者都有 4 个字以上、一句包含另一句；或者都有 3 个字以上、相邻两个字的组合重合 70% 以上
     * （“刚挖到钻石了”和“刚挖到三个钻石”）。
     */
    static boolean similar(String first, String second) {
        String a = normalize(first), b = normalize(second);
        if (a.isEmpty() || b.isEmpty()) return a.equals(b) && first.strip().equals(second.strip());
        if (a.equals(b)) return true;
        int lengthA = a.codePointCount(0, a.length()), lengthB = b.codePointCount(0, b.length());
        if (Math.min(lengthA, lengthB) >= 4 && (a.contains(b) || b.contains(a))) return true;
        if (Math.min(lengthA, lengthB) < 3) return false;
        Set<String> pairsA = pairs(a), pairsB = pairs(b);
        int shared = 0;
        for (String pair : pairsA) if (pairsB.contains(pair)) shared++;
        return 2.0 * shared / (pairsA.size() + pairsB.size()) >= 0.7;
    }

    private static Set<String> pairs(String text) {
        int[] points = text.codePoints().toArray();
        var result = new HashSet<String>();
        for (int index = 0; index + 1 < points.length; index++) result.add(new String(points, index, 2));
        return result;
    }

    /**
     * 名字的简称，熟人之间这样叫：Lemon_lu → lemon，xiCookie → cookie，_Bear777 → bear，tanghao2008 → tanghao。
     * 拆不出 3 个字母以上的部分时用原名。
     */
    static String nick(String name) {
        var parts = name.replaceAll("([a-z])([A-Z])", "$1 $2").split("[^A-Za-z]+");
        String best = null;
        for (String part : parts) if (part.length() >= 3 && (best == null || part.length() > best.length())) best = part;
        return best == null ? name : best.toLowerCase(Locale.ROOT);
    }
}
