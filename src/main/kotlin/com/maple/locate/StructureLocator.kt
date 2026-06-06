package com.maple.locate

import net.minecraft.core.BlockPos
import net.minecraft.core.HolderSet
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.server.level.ServerLevel
import net.minecraft.tags.TagKey
import net.minecraft.world.level.levelgen.structure.Structure
import kotlin.math.sqrt

/**
 * Server-side structure locating service shared by AI tools and direct commands.
 */
object StructureLocator {

    fun locate(
        level: ServerLevel,
        origin: BlockPos,
        structureName: String,
        radius: Int = 100,
        skipKnownStructures: Boolean = false
    ): String {
        val normalizedRadius = radius.coerceIn(1, 500)
        val registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE)
        val resolved = resolveStructureHolderSet(registry, structureName)
            ?: return "未知结构：$structureName。可尝试 village、ancient_city、desert_pyramid、mansion、stronghold、minecraft:village 或 #minecraft:village"

        val found = level.chunkSource.generator.findNearestMapStructure(
            level,
            resolved.holders,
            origin,
            normalizedRadius,
            skipKnownStructures
        ) ?: return "未在 ${normalizedRadius} chunks 内找到结构 ${resolved.displayName}"

        val pos = found.first
        val dx = pos.x - origin.x
        val dz = pos.z - origin.z
        val distance = sqrt((dx * dx + dz * dz).toDouble())
        val foundName = found.second.unwrapKey()
            .map { it.identifier().toString() }
            .orElse(resolved.displayName)
        return "已找到 $foundName：坐标 (${pos.x}, ${pos.y}, ${pos.z})，水平距离约 ${"%.0f".format(distance)} 格"
    }

    private data class StructureLookup(
        val displayName: String,
        val holders: HolderSet<Structure>
    )

    private fun resolveStructureHolderSet(
        registry: net.minecraft.core.Registry<Structure>,
        rawName: String
    ): StructureLookup? {
        val trimmed = rawName.trim().removeSurrounding("\"")
        if (trimmed.isBlank()) return null

        for (candidate in structureNameCandidates(trimmed)) {
            val idText = candidate.removePrefix("#")
            val id = Identifier.tryParse(idText) ?: continue
            if (candidate.startsWith("#")) {
                val holders = registry.getTagOrEmpty(TagKey.create(Registries.STRUCTURE, id)).toList()
                if (holders.isNotEmpty()) {
                    return StructureLookup("#$id", HolderSet.direct(holders))
                }
                continue
            }

            val holder = registry.get(id)
            if (holder.isPresent) {
                return StructureLookup(id.toString(), HolderSet.direct(listOf(holder.get())))
            }

            val tagHolders = registry.getTagOrEmpty(TagKey.create(Registries.STRUCTURE, id)).toList()
            if (tagHolders.isNotEmpty()) {
                return StructureLookup("#$id", HolderSet.direct(tagHolders))
            }
        }

        return null
    }

    private fun structureNameCandidates(name: String): List<String> {
        val normalized = name
            .lowercase()
            .replace(' ', '_')
            .replace('-', '_')
            .removePrefix("structure:")
        val aliases = mapOf(
            "village" to listOf("#minecraft:village", "minecraft:village"),
            "村庄" to listOf("#minecraft:village"),
            "ancient_city" to listOf("minecraft:ancient_city"),
            "古城" to listOf("minecraft:ancient_city"),
            "desert_pyramid" to listOf("minecraft:desert_pyramid"),
            "desert_temple" to listOf("minecraft:desert_pyramid"),
            "沙漠神殿" to listOf("minecraft:desert_pyramid"),
            "mansion" to listOf("minecraft:mansion"),
            "woodland_mansion" to listOf("minecraft:mansion"),
            "林地府邸" to listOf("minecraft:mansion"),
            "stronghold" to listOf("minecraft:stronghold", "#minecraft:eye_of_ender_located"),
            "要塞" to listOf("minecraft:stronghold", "#minecraft:eye_of_ender_located"),
            "shipwreck" to listOf("#minecraft:shipwreck", "minecraft:shipwreck"),
            "沉船" to listOf("#minecraft:shipwreck"),
            "mineshaft" to listOf("#minecraft:mineshaft", "minecraft:mineshaft"),
            "废弃矿井" to listOf("#minecraft:mineshaft"),
            "ruined_portal" to listOf("#minecraft:ruined_portal", "minecraft:ruined_portal"),
            "废弃传送门" to listOf("#minecraft:ruined_portal"),
            "ocean_ruin" to listOf("#minecraft:ocean_ruin"),
            "海底废墟" to listOf("#minecraft:ocean_ruin"),
            "monument" to listOf("minecraft:monument"),
            "ocean_monument" to listOf("minecraft:monument"),
            "海底神殿" to listOf("minecraft:monument"),
            "trial_chambers" to listOf("minecraft:trial_chambers", "#minecraft:on_trial_chambers_maps"),
            "trial_chamber" to listOf("minecraft:trial_chambers", "#minecraft:on_trial_chambers_maps"),
            "试炼密室" to listOf("minecraft:trial_chambers", "#minecraft:on_trial_chambers_maps"),
            "trail_ruins" to listOf("minecraft:trail_ruins"),
            "trail_ruin" to listOf("minecraft:trail_ruins"),
            "fortress" to listOf("minecraft:fortress"),
            "nether_fortress" to listOf("minecraft:fortress"),
            "下界要塞" to listOf("minecraft:fortress"),
            "bastion" to listOf("minecraft:bastion_remnant"),
            "bastion_remnant" to listOf("minecraft:bastion_remnant"),
            "堡垒遗迹" to listOf("minecraft:bastion_remnant"),
            "end_city" to listOf("minecraft:end_city"),
            "末地城" to listOf("minecraft:end_city"),
            "buried_treasure" to listOf("minecraft:buried_treasure", "#minecraft:on_treasure_maps"),
            "宝藏" to listOf("minecraft:buried_treasure", "#minecraft:on_treasure_maps")
        )

        val direct = if (normalized.contains(":") || normalized.startsWith("#")) {
            normalized
        } else {
            "minecraft:$normalized"
        }
        val tag = if (normalized.startsWith("#")) {
            normalized
        } else if (normalized.contains(":")) {
            "#$normalized"
        } else {
            "#minecraft:$normalized"
        }

        return buildList {
            addAll(aliases[normalized].orEmpty())
            add(direct)
            add(tag)
        }.distinct()
    }
}
