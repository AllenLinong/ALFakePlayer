package io.github.dummyplayers;

import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

enum WorkTool {
    PICKAXE(ItemTags.PICKAXES), AXE(ItemTags.AXES), SHOVEL(ItemTags.SHOVELS);

    private final TagKey<Item> tag;

    WorkTool(TagKey<Item> tag) { this.tag = tag; }
    boolean matches(ItemStack stack) { return stack.is(tag); }
    WorkTool next() { return values()[(ordinal() + 1) % values().length]; }
}
