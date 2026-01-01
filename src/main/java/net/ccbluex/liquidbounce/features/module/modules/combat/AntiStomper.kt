/*
 * FDPClient Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/SkidderMC/FDPClient/
 */
package net.ccbluex.liquidbounce.features.module.modules.combat

import net.ccbluex.liquidbounce.event.UpdateEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.Category
import net.ccbluex.liquidbounce.features.module.Module
import net.minecraft.entity.Entity
import net.minecraft.entity.EntityLivingBase
import net.minecraft.entity.player.EntityPlayer
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * AntiStomper - Automatically sneaks when a player or entity is falling above you
 * to prevent being stomped/killed by fall damage PvP techniques.
 */
object AntiStomper : Module("AntiStomper", Category.COMBAT, Category.SubCategory.COMBAT_RAGE, gameDetecting = false) {

    // Configuration values
    private val minStomperFallDistance by float("MinStomperFallDistance", 2.0f, 0.5f..10.0f)
    private val minHeightAbove by float("MinHeightAbove", 2.0f, 1.0f..5.0f)
    private val maxHeightAbove by float("MaxHeightAbove", 50.0f, 10.0f..100.0f)
    private val horizontalRange by float("HorizontalRange", 5.0f, 2.0f..10.0f)
    private val maxHorizontalDistance by float("MaxHorizontalDistance", 10.0f, 5.0f..20.0f)
    private val releaseDelay by int("ReleaseDelay", 500, 0..2000)

    // Target options
    private val targetEntities by boolean("TargetEntities", false)
    private val debug by boolean("Debug", false)

    // The entity being tracked as falling threat
    private var fallingEntity: Entity? = null

    // Whether we forcefully pressed sneak
    private var isSneaking = false

    // Time when entity landed (for delay)
    private var entityLandedTime: Long = 0L

    // Whether we're waiting for the release delay
    private var waitingForRelease = false

    val onUpdate = handler<UpdateEvent> {
        val thePlayer = mc.thePlayer ?: return@handler
        val theWorld = mc.theWorld ?: return@handler

        // If we're waiting for release delay
        if (waitingForRelease) {
            if (System.currentTimeMillis() - entityLandedTime >= releaseDelay) {
                stopSneaking()
                fallingEntity = null
                waitingForRelease = false
            }
            return@handler
        }

        // If we're currently tracking a falling entity
        val currentThreat = fallingEntity
        if (currentThreat != null) {
            // Check if the threat is over (entity landed, below us, or too far)
            val stopReason = getStopReason(currentThreat, thePlayer)
            if (stopReason != StopReason.NONE) {
                if (stopReason == StopReason.LANDED) {
                    // Entity landed - start the release delay timer
                    entityLandedTime = System.currentTimeMillis()
                    waitingForRelease = true
                } else {
                    // Other reasons (dead, too far) - stop immediately
                    stopSneaking()
                    fallingEntity = null
                }
            }
        } else {
            // Scan for new falling threats
            val threat = if (targetEntities) {
                // Scan all living entities
                detectFallingEntityThreat(theWorld.loadedEntityList, thePlayer)
            } else {
                // Scan only players
                detectFallingPlayerThreat(theWorld.playerEntities, thePlayer)
            }

            if (threat != null) {
                fallingEntity = threat
                startSneaking()
            }
        }
    }

    /**
     * Reasons to stop sneaking
     */
    private enum class StopReason {
        NONE,       // Don't stop
        LANDED,     // Entity landed on ground or below us
        DEAD,       // Entity is dead
        TOO_FAR     // Entity moved too far away
    }

    /**
     * Detects if there's a player falling above the local player
     *
     * @return The falling player, or null if no threat
     */
    private fun detectFallingPlayerThreat(players: List<EntityPlayer>, thePlayer: EntityPlayer): Entity? {
        return players.firstOrNull { player ->
            // Skip ourselves
            if (player == thePlayer) return@firstOrNull false

            isEntityFallingThreat(player, thePlayer)
        }
    }

    /**
     * Detects if there's any entity falling above the local player
     *
     * @return The falling entity, or null if no threat
     */
    private fun detectFallingEntityThreat(entities: List<Entity>, thePlayer: EntityPlayer): Entity? {
        return entities.firstOrNull { entity ->
            // Skip ourselves and non-living entities
            if (entity == thePlayer || entity !is EntityLivingBase) return@firstOrNull false

            isEntityFallingThreat(entity, thePlayer)
        }
    }

    /**
     * Checks if a given entity is a falling threat (stomper)
     *
     * For a real stomper threat, we need:
     * 1. Entity must NOT be on ground (actually falling/in air)
     * 2. Entity must be significantly above us (minHeightAbove)
     * 3. Entity must be within horizontal range (could land on us)
     * 4. Entity must be falling (detected by fallDistance OR position delta)
     *
     * @return true if the entity is a stomp threat
     */
    private fun isEntityFallingThreat(entity: Entity, thePlayer: EntityPlayer): Boolean {
        // CRITICAL: Entity must NOT be on ground - if they're on ground, they're just walking
        // This prevents false positives when someone is walking on a platform above us
        if (entity.onGround) return false

        // Check if entity is above us by a significant height
        val heightDiff = entity.posY - thePlayer.posY
        if (heightDiff < minHeightAbove) return false

        // Check if entity is too high (probably on a building, not stomping)
        if (heightDiff > maxHeightAbove) return false

        // Check if entity is within horizontal range (could land on us)
        val horizontalDist = calculateHorizontalDistance(entity, thePlayer)
        if (horizontalDist >= horizontalRange) return false

        // Multiple detection methods:
        // 1. fallDistance (synced by server for other players)
        val hasSignificantFall = entity.fallDistance >= minStomperFallDistance

        // 2. Position delta (entity moving downward) - must be significant to avoid walking slopes
        val positionDelta = entity.prevPosY - entity.posY
        val isMovingDown = positionDelta > 0.3  // Increased threshold to avoid walking detection

        // 3. motionY (may not be reliable for other players but worth checking)
        val isFallingByMotion = entity.motionY < -0.3  // Increased threshold for actual falling

        // Debug output
        if (debug && (hasSignificantFall || isMovingDown || isFallingByMotion)) {
            val name = if (entity is EntityPlayer) entity.name else entity.javaClass.simpleName
            mc.thePlayer?.addChatMessage(net.minecraft.util.ChatComponentText(
                "§7[AntiStomper] §f$name: height=${"%.1f".format(heightDiff)}, " +
                        "fall=${"%.1f".format(entity.fallDistance)}, delta=${"%.2f".format(positionDelta)}, " +
                        "motionY=${"%.2f".format(entity.motionY)}, onGround=${entity.onGround}"
            ))
        }

        // Trigger if any of these indicate falling:
        // - Has significant fall distance (most reliable for server-synced data)
        // - OR is moving down significantly AND above minimum height
        return hasSignificantFall || (isMovingDown && heightDiff >= minHeightAbove * 1.5) || (isFallingByMotion && heightDiff >= minHeightAbove * 1.5)
    }

    /**
     * Gets the reason why we should stop sneaking
     *
     * @return StopReason indicating why to stop, or NONE if we shouldn't stop
     */
    private fun getStopReason(threat: Entity, thePlayer: EntityPlayer): StopReason {
        // Entity is dead
        if (threat.isDead) return StopReason.DEAD

        // Entity is on ground or below us (landed)
        if (threat.onGround || threat.posY <= thePlayer.posY) return StopReason.LANDED

        // Entity is no longer falling (or going up) - consider as landed
        if (threat.motionY >= 0) return StopReason.LANDED

        // Entity moved too far away horizontally
        val horizontalDist = calculateHorizontalDistance(threat, thePlayer)
        if (horizontalDist > maxHorizontalDistance) return StopReason.TOO_FAR

        return StopReason.NONE
    }

    /**
     * Calculates the horizontal distance between two entities
     */
    private fun calculateHorizontalDistance(entity1: Entity, entity2: Entity): Double {
        return sqrt(
            (entity1.posX - entity2.posX).pow(2) +
                    (entity1.posZ - entity2.posZ).pow(2)
        )
    }

    /**
     * Starts pressing sneak
     */
    private fun startSneaking() {
        if (!isSneaking) {
            mc.gameSettings.keyBindSneak.pressed = true
            isSneaking = true
        }
    }

    /**
     * Releases sneak key
     */
    private fun stopSneaking() {
        if (isSneaking) {
            mc.gameSettings.keyBindSneak.pressed = false
            isSneaking = false
        }
    }

    override fun onDisable() {
        // Make sure we release sneak when module is disabled
        stopSneaking()
        fallingEntity = null
        waitingForRelease = false
        entityLandedTime = 0L
    }
}

