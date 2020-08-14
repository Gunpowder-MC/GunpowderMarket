/*
 * MIT License
 *
 * Copyright (c) 2020 GunpowderMC
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package io.github.gunpowder.commands

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.context.CommandContext
import io.github.gunpowder.api.GunpowderMod
import io.github.gunpowder.api.builders.ChestGui
import io.github.gunpowder.api.builders.Command
import io.github.gunpowder.api.module.currency.modelhandlers.BalanceHandler
import io.github.gunpowder.api.module.market.dataholders.StoredMarketEntry
import io.github.gunpowder.api.module.market.modelhandlers.MarketEntryHandler
import io.github.gunpowder.configs.MarketConfig
import net.fabricmc.fabric.api.util.NbtType
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.nbt.ListTag
import net.minecraft.network.packet.s2c.play.OpenScreenS2CPacket
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.LiteralText
import net.minecraft.util.ItemScatterer
import java.time.LocalDateTime

object MarketCommand {
    private val marketHandler by lazy {
        GunpowderMod.instance.registry.getModelHandler(MarketEntryHandler::class.java)
    }
    private val balanceHandler by lazy {
        GunpowderMod.instance.registry.getModelHandler(BalanceHandler::class.java)
    }

    private val maxEntriesPerUser
        get() = GunpowderMod.instance.registry.getConfig(MarketConfig::class.java).maxMarketsPerUser

    fun register(dispatcher: CommandDispatcher<ServerCommandSource>) {
        Command.builder(dispatcher) {
            command("market", "m") {
                executes(MarketCommand::viewMarket)

                literal("add", "a") {
                    argument("price", DoubleArgumentType.doubleArg(0.0)) {
                        executes(::addMarketOne)

                        argument("amount", IntegerArgumentType.integer(0, 64)) {
                            executes(::addMarketAmount)
                        }
                    }
                }

                literal("expired") {
                    executes(::collectMarketExpired)
                }
            }
        }
    }

    private fun collectMarketExpired(context: CommandContext<ServerCommandSource>): Int {
        val player = context.source.player
        val entries = marketHandler.getEntries().filter { it.expire.isBefore(LocalDateTime.now()) }

        entries.forEach { entry ->
            marketHandler.deleteEntry(entry)

            // Remove listing data
            val item = entry.item
            val tag = item.tag!!
            val display = tag.getCompound("display")
            val lore = display.getList("Lore", NbtType.STRING) as ListTag

            lore.removeAt(3)
            lore.removeAt(2)
            lore.removeAt(1)
            lore.removeAt(0)

            // Remove tags if nothing's left
            if (lore.isEmpty()) {
                display.remove("Lore")
            } else {
                display.put("Lore", lore)
            }
            if (display.isEmpty) {
                tag.remove("display")
            } else {
                tag.put("display", display)
            }
            if (tag.isEmpty) {
                item.tag = null
            } else {
                item.tag = tag
            }

            // Give item
            if (player.giveItemStack(item)) {
                // Unable to insert
                ItemScatterer.spawn(player.world, player.x, player.y, player.z, item)
            }
        }

        return 1
    }

    private fun addMarketOne(context: CommandContext<ServerCommandSource>): Int {
        return addMarket(context, 1)
    }

    private fun addMarketAmount(context: CommandContext<ServerCommandSource>): Int {
        return addMarket(context, IntegerArgumentType.getInteger(context, "amount"))
    }

    private fun addMarket(context: CommandContext<ServerCommandSource>, amount: Int): Int {
        if (marketHandler.getEntries().count { it.uuid == context.source.player.uuid } >= maxEntriesPerUser) {
            context.source.sendError(LiteralText("You already have the maximum of $maxEntriesPerUser entries"))
            return -1
        }

        val item = context.source.player.mainHandStack.copy()

        if (item.count < amount) {
            context.source.sendError(LiteralText("Your hand doesn't contain $amount items!"))
            return -1
        }

        if (item.item == Items.AIR) {
            context.source.sendError(LiteralText("You are not holding anything!"))
            return -1
        }

        context.source.player.mainHandStack.count = item.count - amount
        item.count = amount

        val entry = StoredMarketEntry(
                context.source.player.uuid,
                item,
                DoubleArgumentType.getDouble(context, "price").toBigDecimal(),
                LocalDateTime.now().plusDays(7)
        )

        // Remove from user invstack
        marketHandler.createEntry(entry)

        return 1
    }

    private fun viewMarket(context: CommandContext<ServerCommandSource>): Int {
        val entries = marketHandler.getEntries().filter { it.expire.isAfter(LocalDateTime.now()) }
        val maxPages = entries.size / 45
        try {
            openGui(context, entries, 0, maxPages)
        } catch (e: Exception) {
            e.printStackTrace()
            throw e
        }
        return 1
    }

    private fun openGui(context: CommandContext<ServerCommandSource>, entries: List<StoredMarketEntry>, page: Int, maxPage: Int) {
        val player = context.source.player
        player.closeCurrentScreen()

        val gui = ChestGui.builder {
            player(context.source.player)
            emptyIcon(ItemStack(Items.BLACK_STAINED_GLASS_PANE))

            refreshInterval(1) {
                it.clearButtons()
                val ets = marketHandler.getEntries().filter { itt -> itt.expire.isAfter(LocalDateTime.now()) }
                val etsDisplay = ets.subList(page * 45, Integer.min((page + 1) * 45, ets.size))
                etsDisplay.forEachIndexed { index, storedMarketEntry ->
                    it.button(index % 9, index / 9, storedMarketEntry.item) {
                        buyItem(player, storedMarketEntry)
                    }
                }

                // Navigation
                for (i in 0 until 9) {
                    // Filler
                    it.button(i, 5, ItemStack(Items.GREEN_STAINED_GLASS_PANE)) { }
                }

                if (maxPage != 0) {
                    it.button(0, 5, ItemStack(Items.BLUE_STAINED_GLASS_PANE).setCustomName(LiteralText("Previous page"))) {
                        val prevPage = if (page == 0) maxPage else page - 1
                        try {
                            openGui(context, ets, prevPage, maxPage)
                        } catch (e: StackOverflowError) {
                            player.closeCurrentScreen()
                        }
                    }

                    it.button(8, 5, ItemStack(Items.BLUE_STAINED_GLASS_PANE).setCustomName(LiteralText("Next page"))) {
                        val nextPage = if (page == maxPage) 0 else page + 1
                        try {
                            openGui(context, ets, nextPage, maxPage)
                        } catch (e: StackOverflowError) {
                            player.closeCurrentScreen()
                        }
                    }
                }
            }

            // Add all market buttons
            val itemsOnDisplay = entries.subList(page * 45, Integer.min((page + 1) * 45, entries.size))
            itemsOnDisplay.forEachIndexed { index, storedMarketEntry ->
                button(index % 9, index / 9, storedMarketEntry.item) {
                    buyItem(player, storedMarketEntry)
                }
            }

            // Navigation
            for (i in 0 until 9) {
                // Filler
                button(i, 5, ItemStack(Items.GREEN_STAINED_GLASS_PANE)) { }
            }

            if (maxPage != 0) {
                button(0, 5, ItemStack(Items.BLUE_STAINED_GLASS_PANE).setCustomName(LiteralText("Previous page"))) {
                    val prevPage = if (page == 0) maxPage else page - 1
                    try {
                        openGui(context, entries, prevPage, maxPage)
                    } catch (e: StackOverflowError) {
                        player.closeCurrentScreen()
                    }
                }

                button(8, 5, ItemStack(Items.BLUE_STAINED_GLASS_PANE).setCustomName(LiteralText("Next page"))) {
                    val nextPage = if (page == maxPage) 0 else page + 1
                    try {
                        openGui(context, entries, nextPage, maxPage)
                    } catch (e: StackOverflowError) {
                        player.closeCurrentScreen()
                    }
                }
            }
        }

        player.networkHandler.sendPacket(
                OpenScreenS2CPacket(gui.syncId, gui.type, LiteralText("Market")))
        gui.addListener(player)
        player.currentScreenHandler = gui
    }

    private fun buyItem(player: ServerPlayerEntity, entry: StoredMarketEntry) {
        val balance = balanceHandler.getUser(player.uuid).balance

        // Check if user has enough money
        if (balance < entry.price) {
            player.sendMessage(LiteralText("Not enough money!"), false)
        } else {
            // Check if still present
            if (marketHandler.getEntries().contains(entry)) {
                marketHandler.deleteEntry(entry)
                balanceHandler.modifyUser(player.uuid) {
                    it.balance -= entry.price
                    it
                }
                balanceHandler.modifyUser(entry.uuid) {
                    it.balance += entry.price
                    it
                }

                // Remove listing data
                val item = entry.item
                val tag = item.tag!!
                val display = tag.getCompound("display")
                val lore = display.getList("Lore", NbtType.STRING) as ListTag

                lore.removeAt(3)
                lore.removeAt(2)
                lore.removeAt(1)
                lore.removeAt(0)

                // Remove tags if nothing's left
                if (lore.isEmpty()) {
                    display.remove("Lore")
                } else {
                    display.put("Lore", lore)
                }
                if (display.isEmpty) {
                    tag.remove("display")
                } else {
                    tag.put("display", display)
                }
                if (tag.isEmpty) {
                    item.tag = null
                } else {
                    item.tag = tag
                }

                // Give item
                if (player.giveItemStack(item)) {
                    // Unable to insert
                    ItemScatterer.spawn(player.world, player.x, player.y, player.z, item)
                }

                player.sendMessage(
                        LiteralText("Successfully purchased item!"),
                        false)
                player.server.playerManager.getPlayer(entry.uuid)?.sendMessage(
                        LiteralText("${player.entityName} purchased one of your items!"),
                        false)

            } else {
                player.sendMessage(LiteralText("Item no longer available"), false)
            }
        }
    }
}
