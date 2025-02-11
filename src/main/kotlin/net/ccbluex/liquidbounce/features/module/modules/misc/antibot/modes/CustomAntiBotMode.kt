/*
 * This file is part of LiquidBounce (https://github.com/CCBlueX/LiquidBounce)
 *
 * Copyright (c) 2015 - 2024 CCBlueX
 *
 * LiquidBounce is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * LiquidBounce is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with LiquidBounce. If not, see <https://www.gnu.org/licenses/>.
 */
package net.ccbluex.liquidbounce.features.module.modules.misc.antibot.modes

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap
import it.unimi.dsi.fastutil.ints.IntOpenHashSet
import net.ccbluex.liquidbounce.config.Choice
import net.ccbluex.liquidbounce.config.ChoiceConfigurable
import net.ccbluex.liquidbounce.config.ToggleableConfigurable
import net.ccbluex.liquidbounce.event.events.AttackEvent
import net.ccbluex.liquidbounce.event.events.PacketEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.event.repeatable
import net.ccbluex.liquidbounce.features.module.modules.misc.antibot.ModuleAntiBot
import net.ccbluex.liquidbounce.features.module.modules.misc.antibot.ModuleAntiBot.isADuplicate
import net.ccbluex.liquidbounce.utils.math.sq
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.network.packet.s2c.play.EntitiesDestroyS2CPacket
import net.minecraft.network.packet.s2c.play.EntityAnimationS2CPacket
import net.minecraft.network.packet.s2c.play.EntityAttributesS2CPacket
import net.minecraft.network.packet.s2c.play.EntityS2CPacket
import kotlin.math.abs

object CustomAntiBotMode : Choice("Custom"), ModuleAntiBot.IAntiBotMode {

    override val parent: ChoiceConfigurable<*>
        get() = ModuleAntiBot.modes

    private object InvalidGround : ToggleableConfigurable(ModuleAntiBot, "InvalidGround", true) {
        val vlToConsiderAsBot by int("VLToConsiderAsBot", 10, 1..50, "flags")
    }

    init {
        tree(InvalidGround)
        tree(AlwaysInRadius)
    }

    private val duplicate by boolean("Duplicate", false)
    private val noGameMode by boolean("NoGameMode", true)
    private val illegalPitch by boolean("IllegalPitch", true)
    private val fakeEntityID by boolean("FakeEntityID", true)
    private val illegalName by boolean("IllegalName", true)
    private val needHit by boolean("NeedHit", false)
    private val health by boolean("IllegalHealth", false)
    private val swung by boolean("Swung", false)
    private val critted by boolean("Critted", false)
    private val attributes by boolean("Attributes", false)

    private object AlwaysInRadius : ToggleableConfigurable(ModuleAntiBot, "AlwaysInRadius", false) {
        val alwaysInRadiusRange by float("AlwaysInRadiusRange", 20f, 5f..30f)
    }

    private val flyingSet = Int2IntOpenHashMap()
    private val hitListSet = IntOpenHashSet()
    private val notAlwaysInRadiusSet = IntOpenHashSet()

    private val swungSet = IntOpenHashSet()
    private val crittedSet = IntOpenHashSet()
    private val attributesSet = IntOpenHashSet()

    val repeatable = repeatable {
        val rangeSquared = AlwaysInRadius.alwaysInRadiusRange.sq()
        for (entity in world.players) {
            if (player.squaredDistanceTo(entity) > rangeSquared) {
                notAlwaysInRadiusSet.add(entity.id)
            }
        }
    }

    @Suppress("unused")
    private val attackHandler = handler<AttackEvent> {
        hitListSet.add(it.enemy.id)
    }

    @Suppress("unused")
    private val packetHandler = handler<PacketEvent> { event ->
        when (val packet = event.packet) {
            is EntityS2CPacket -> {
                if (!packet.isPositionChanged || !InvalidGround.enabled) {
                    return@handler
                }

                val entity = packet.getEntity(world) ?: return@handler
                val currentValue = flyingSet.getOrDefault(entity.id, 0)
                if (entity.isOnGround && entity.prevY != entity.y) {
                    flyingSet.put(entity.id, currentValue + 1)
                } else if (!entity.isOnGround && currentValue > 0) {
                    val newVL = currentValue / 2

                    if (newVL <= 0) {
                        flyingSet.remove(entity.id)
                    } else {
                        flyingSet.put(entity.id, newVL)
                    }
                }
            }

            is EntityAttributesS2CPacket -> {
                attributesSet.add(packet.entityId)
            }

            is EntityAnimationS2CPacket -> {
                val animationId = packet.animationId

                if (animationId == EntityAnimationS2CPacket.SWING_MAIN_HAND ||
                    animationId == EntityAnimationS2CPacket.SWING_OFF_HAND) {
                    swungSet.add(packet.entityId)
                } else if (animationId == EntityAnimationS2CPacket.CRIT ||
                    animationId == EntityAnimationS2CPacket.ENCHANTED_HIT) {
                    crittedSet.add(packet.entityId)
                }
            }

            is EntitiesDestroyS2CPacket -> {
                packet.entityIds.intIterator().apply {
                    while (hasNext()) {
                        val entityId = nextInt()
                        attributesSet.remove(entityId)
                        flyingSet.remove(entityId)
                        hitListSet.remove(entityId)
                        notAlwaysInRadiusSet.remove(entityId)
                    }
                }
            }
        }


    }

    private fun hasInvalidGround(player: PlayerEntity): Boolean {
        return flyingSet.getOrDefault(player.id, 0) >= InvalidGround.vlToConsiderAsBot
    }

    private const val VALID_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_"

    private fun hasIllegalName(player: PlayerEntity): Boolean {
        val name = player.nameForScoreboard

        if (name.length < 3 || name.length > 16) {
            return true
        }

        return name.any { it !in VALID_CHARS }
    }

    @Suppress("all")
    private fun meetsCustomConditions(player: PlayerEntity): Boolean {
        return when {
            noGameMode && network.getPlayerListEntry(player.uuid)?.gameMode == null -> true
            InvalidGround.enabled && hasInvalidGround(player) -> true
            fakeEntityID && (player.id < 0 || player.id >= 1E+9) -> true
            duplicate && isADuplicate(player.gameProfile) -> true
            illegalName && hasIllegalName(player) -> true
            illegalPitch && abs(player.pitch) > 90 -> true
            AlwaysInRadius.enabled && !notAlwaysInRadiusSet.contains(player.id) -> true
            needHit && !hitListSet.contains(player.id) -> true
            health && player.health > 20f -> true
            swung && !swungSet.contains(player.id) -> true
            critted && !crittedSet.contains(player.id) -> true
            attributes && !attributesSet.contains(player.id) -> true
            else -> false
        }
    }

    override fun isBot(entity: PlayerEntity): Boolean {
        return meetsCustomConditions(entity)
    }

    override fun reset() {
        flyingSet.clear()
        notAlwaysInRadiusSet.clear()
        hitListSet.clear()
        swungSet.clear()
        crittedSet.clear()
        attributesSet.clear()
    }
}
