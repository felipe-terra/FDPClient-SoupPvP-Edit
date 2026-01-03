/*
 * FDPClient Hacked Client
 * A free open source mixin-based injection hacked client for Minecraft using Minecraft Forge.
 * https://github.com/SkidderMC/FDPClient/
 */
package net.ccbluex.liquidbounce.features.module.modules.combat

import kotlinx.coroutines.delay
import net.ccbluex.liquidbounce.event.GameTickEvent
import net.ccbluex.liquidbounce.event.handler
import net.ccbluex.liquidbounce.features.module.Category
import net.ccbluex.liquidbounce.features.module.Module
import net.ccbluex.liquidbounce.utils.client.chat
import net.ccbluex.liquidbounce.utils.inventory.InventoryUtils.isFirstInventoryClick
import net.ccbluex.liquidbounce.utils.inventory.InventoryUtils.serverOpenInventory
import net.ccbluex.liquidbounce.utils.timing.TickedActions.awaitTicked
import net.ccbluex.liquidbounce.utils.timing.TickedActions.clickNextTick
import net.ccbluex.liquidbounce.event.async.launchSequence
import net.minecraft.client.gui.inventory.GuiInventory
import net.minecraft.init.Blocks
import net.minecraft.init.Items
import net.minecraft.item.Item
import org.lwjgl.input.Keyboard

object AutoRecraft : Module("AutoRecraft", Category.COMBAT, Category.SubCategory.COMBAT_LEGIT) {

    // Trigger key (different from module toggle key)
    private val triggerKey by int("TriggerKey", Keyboard.KEY_R, 0..255)
    
    // Delay between inventory clicks
    private val clickDelay by intRange("Delay", 50..100, 0..500)
    
    // Open inventory visually or simulate
    private val openInventory by boolean("OpenInventory", false)
    private val simulateInventory by boolean("SimulateInventory", true) { !openInventory }
    
    // Start delay for first click (important for anti-cheat)
    private val startDelay by int("StartDelay", 100, 0..500)
    
    // Auto close inventory after crafting
    private val autoClose by boolean("AutoClose", true)
    private val closeDelay by int("CloseDelay", 100, 0..500) { autoClose }
    
    // Priority: cocoa -> cactus -> mushroom
    private val priorityMode by choices("Priority", arrayOf("CocoaFirst", "CactusFirst", "MushroomFirst"), "MushroomFirst")
    
    // State management
    private var isProcessing = false
    private var wasKeyDown = false
    
    // Crafting state
    private var craftingType: CraftingType? = null
    private var ingredient1Slot = -1
    private var ingredient2Slot = -1
    private var bowlSlot = -1
    
    private enum class CraftingType {
        MUSHROOM,   // red_mushroom + brown_mushroom + bowl (2 different ingredients)
        COCOA,      // cocoa_beans + bowl (1 ingredient only)
        CACTUS      // cactus + bowl (1 ingredient only)
    }
    
    /*
     * Inventory Slot Layout (windowId = 0):
     * Slot 0: Crafting Output
     * Slots 1-4: Crafting Grid 2x2
     *   [1] [2]
     *   [3] [4]
     * Slots 5-8: Armor slots
     * Slot 45: Offhand
     * Slots 9-35: Main Inventory
     * Slots 36-44: Hotbar
     */
    
    override fun onDisable() {
        resetState()
    }
    
    private fun resetState() {
        isProcessing = false
        craftingType = null
        ingredient1Slot = -1
        ingredient2Slot = -1
        bowlSlot = -1
    }
    
    val onGameTick = handler<GameTickEvent> {
        val thePlayer = mc.thePlayer ?: return@handler
        
        val isKeyDown = Keyboard.isKeyDown(triggerKey)
        
        // Detect key press (not hold) - trigger on key down, not while held
        if (isKeyDown && !wasKeyDown && !isProcessing) {
            startCrafting()
        }
        
        wasKeyDown = isKeyDown
    }
    
    private fun startCrafting() {
        val thePlayer = mc.thePlayer ?: return
        
        // Find ingredients based on priority
        val found = findIngredients()
        if (!found) {
            chat("§c[AutoRecraft] No ingredients found for any soup recipe!")
            return
        }
        
        isProcessing = true
        
        // Open inventory if needed
        if (openInventory && mc.currentScreen !is GuiInventory) {
            mc.displayGuiScreen(GuiInventory(thePlayer))
        }
        
        // Start crafting coroutine
        launchSequence {
            craftSoups()
        }
    }
    
    private fun findIngredients(): Boolean {
        val thePlayer = mc.thePlayer ?: return false
        
        // Get priority order
        val priorities = when (priorityMode) {
            "CocoaFirst" -> listOf(CraftingType.COCOA, CraftingType.CACTUS, CraftingType.MUSHROOM)
            "CactusFirst" -> listOf(CraftingType.CACTUS, CraftingType.COCOA, CraftingType.MUSHROOM)
            else -> listOf(CraftingType.MUSHROOM, CraftingType.COCOA, CraftingType.CACTUS)
        }
        
        for (type in priorities) {
            when (type) {
                CraftingType.MUSHROOM -> {
                    val redMushroom = findItemSlot(Item.getItemFromBlock(Blocks.red_mushroom), 9, 44)
                    val brownMushroom = findItemSlot(Item.getItemFromBlock(Blocks.brown_mushroom), 9, 44)
                    val bowl = findItemSlot(Items.bowl, 9, 44)
                    
                    if (redMushroom != -1 && brownMushroom != -1 && bowl != -1) {
                        craftingType = CraftingType.MUSHROOM
                        ingredient1Slot = redMushroom
                        ingredient2Slot = brownMushroom
                        bowlSlot = bowl
                        return true
                    }
                }
                CraftingType.COCOA -> {
                    val cocoa = findItemSlot(Items.dye, 9, 44, metadata = 3) // Cocoa beans have metadata 3
                    val bowl = findItemSlot(Items.bowl, 9, 44)
                    
                    if (cocoa != -1 && bowl != -1) {
                        craftingType = CraftingType.COCOA
                        ingredient1Slot = cocoa
                        ingredient2Slot = -1 // No second ingredient needed
                        bowlSlot = bowl
                        return true
                    }
                }
                CraftingType.CACTUS -> {
                    val cactus = findItemSlot(Item.getItemFromBlock(Blocks.cactus), 9, 44)
                    val bowl = findItemSlot(Items.bowl, 9, 44)
                    
                    if (cactus != -1 && bowl != -1) {
                        craftingType = CraftingType.CACTUS
                        ingredient1Slot = cactus
                        ingredient2Slot = -1 // No second ingredient needed
                        bowlSlot = bowl
                        return true
                    }
                }
            }
        }
        
        return false
    }
    
    private fun findItemSlot(item: Item, startSlot: Int, endSlot: Int, metadata: Int = -1): Int {
        val thePlayer = mc.thePlayer ?: return -1
        
        for (slot in startSlot..endSlot) {
            val stack = thePlayer.openContainer.getSlot(slot).stack ?: continue
            if (stack.item == item) {
                if (metadata == -1 || stack.metadata == metadata) {
                    return slot
                }
            }
        }
        return -1
    }
    
    private suspend fun craftSoups() {
        try {
            // Craft as many soups as possible
            while (handleEvents()) {
                // Re-find ingredients for next soup
                if (!findIngredients()) {
                    break
                }
                
                // Craft one soup
                craftOneSoup()
                
                // Wait for all clicks to be processed
                awaitTicked()
                
                // Small delay between craft cycles
                delay(clickDelay.random().toLong())
            }
            
            // Cleanup any remaining items in craft grid
            cleanupCraftGrid()
            awaitTicked()
            
            // Close inventory if needed
            if (autoClose) {
                delay(closeDelay.toLong())
                closeInventory()
            }
            
            chat("§a[AutoRecraft] Crafting complete!")
        } finally {
            resetState()
        }
    }
    
    private suspend fun craftOneSoup() {
        val thePlayer = mc.thePlayer ?: return
        
        /*
         * Crafting steps:
         * For Mushroom: ingredient1 -> slot1, ingredient2 -> slot2, bowl -> slot3
         * For Cocoa/Cactus: ingredient1 -> slot1, bowl -> slot3
         */
        
        // Step 1: Right-click ingredient1 to pick up half, then place in slot 1
        click(ingredient1Slot, 1, 0) // Right-click to pick up half
        click(1, 0, 0) // Left-click to place in craft slot 1
        
        // Step 2: For mushroom only - right-click ingredient2 and place in slot 2
        if (craftingType == CraftingType.MUSHROOM && ingredient2Slot != -1) {
            click(ingredient2Slot, 1, 0) // Right-click to pick up half
            click(2, 0, 0) // Left-click to place in craft slot 2
        }
        
        // Step 3: Right-click bowl and place in slot 3
        click(bowlSlot, 1, 0) // Right-click to pick up half
        click(3, 0, 0) // Left-click to place in craft slot 3
        
        // Wait for clicks to be processed before collecting output
        awaitTicked()
        
        // Step 4: Shift-click output to collect soup
        click(0, 0, 1) // Shift + left-click
    }
    
    private suspend fun cleanupCraftGrid() {
        val thePlayer = mc.thePlayer ?: return
        
        // Check craft grid slots (1-4) and move any remaining items back
        for (craftSlot in 1..4) {
            val stack = thePlayer.openContainer.getSlot(craftSlot).stack
            if (stack != null && stack.stackSize > 0) {
                // Shift-click to move back to inventory
                click(craftSlot, 0, 1)
            }
        }
    }
    
    private suspend fun click(slot: Int, button: Int, mode: Int) {
        if (!handleEvents()) return
        
        // Open inventory on server-side if needed
        if (simulateInventory || openInventory) {
            serverOpenInventory = true
        }
        
        // First click needs start delay (important for anti-cheat)
        if (isFirstInventoryClick) {
            delay(startDelay.toLong())
        }
        
        // Schedule click for next tick (proper synchronization with server)
        clickNextTick(slot, button, mode, allowDuplicates = true)
        
        // Delay between clicks
        delay(clickDelay.random().toLong())
    }
    
    private fun closeInventory() {
        val thePlayer = mc.thePlayer ?: return
        
        if (openInventory && mc.currentScreen is GuiInventory) {
            thePlayer.closeScreen()
        } else if (simulateInventory) {
            serverOpenInventory = false
        }
    }
    
    override val tag: String
        get() = priorityMode
}
