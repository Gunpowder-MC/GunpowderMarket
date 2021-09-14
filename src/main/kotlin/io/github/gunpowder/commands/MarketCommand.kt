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
import io.github.gunpowder.api.builders.SidebarInfo
import io.github.gunpowder.api.ext.getPermission
import io.github.gunpowder.api.ext.getPresentPermission
import io.github.gunpowder.api.util.TranslatedText
import io.github.gunpowder.configs.MarketConfig
import io.github.gunpowder.entities.StoredMarketEntry
import io.github.gunpowder.modelhandlers.BalanceHandler
import io.github.gunpowder.modelhandlers.MarketEntryHandler
import net.fabricmc.fabric.api.util.NbtType
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.entity.player.PlayerInventory
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.nbt.NbtElement
import net.minecraft.nbt.NbtList
import net.minecraft.nbt.NbtString
import net.minecraft.screen.NamedScreenHandlerFactory
import net.minecraft.screen.ScreenHandler
import net.minecraft.server.command.ServerCommandSource
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.text.LiteralText
import net.minecraft.text.Text
import net.minecraft.util.Formatting
import net.minecraft.util.ItemScatterer
import java.time.Duration
import java.time.LocalDateTime
import java.util.*
import kotlin.concurrent.thread

object MarketCommand {
    private val marketHandler by lazy {
        MarketEntryHandler
    }
    private val balanceHandler by lazy {
        BalanceHandler
    }

    private val EMPTY: ItemStack
        get() = ItemStack(Items.BLACK_STAINED_GLASS_PANE)

    private val maxEntriesPerUser
        get() = GunpowderMod.instance.registry.getConfig(MarketConfig::class.java).maxMarketsPerUser

    fun register(dispatcher: CommandDispatcher<ServerCommandSource>) {
        Command.builder(dispatcher) {
            command("market", "m") {
                permission("market.view", 0)

                executes(MarketCommand::viewMarket)

                literal("add", "a") {
                    permission("market.add", 0)
                    argument("price", DoubleArgumentType.doubleArg(0.0)) {
                        executes(::addMarketOne)

                        argument("amount", IntegerArgumentType.integer(0, 64)) {
                            executes(::addMarketAmount)
                        }
                    }
                }

                literal("expired") {
                    permission("market.add", 0)
                    executes(::collectMarketExpired)
                }
            }
        }
    }

    private fun collectMarketExpired(context: CommandContext<ServerCommandSource>): Int {
        val player = context.source.player
        val entries = marketHandler.getEntries().filter { it.expire.isBefore(LocalDateTime.now()) && it.uuid == player.uuid }

        entries.forEach { entry ->
            marketHandler.deleteEntry(entry)

            // Remove listing data
            val item = entry.item
            val tag = item.nbt!!
            val display = tag.getCompound("display")
            val lore = display.getList("Lore", NbtType.STRING) as NbtList

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
                item.nbt = null
            } else {
                item.nbt = tag
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
        val maxEntries = context.source.player.getPermission("market.limit.[int]", maxEntriesPerUser)
        if (marketHandler.getEntries().count { it.uuid == context.source.player.uuid } >= maxEntries) {
            context.source.sendError(LiteralText("You already have the maximum of $maxEntries entries"))
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

        marketHandler.createEntry(entry)


        return 1
    }

    private fun viewMarket(context: CommandContext<ServerCommandSource>): Int {
        val entries = marketHandler.getEntries().filter { it.expire.isAfter(LocalDateTime.now()) }

        try {
            openGui(context, entries, 0)
        } catch (e: Exception) {
            e.printStackTrace()
            throw e
        }
        return 1
    }

    private fun openGui(context: CommandContext<ServerCommandSource>, entries: List<StoredMarketEntry>, p: Int) {

        val player = context.source.player

        var page = p
        var maxPage = entries.size / 45

        fun reload(this_: ChestGui.Container) {
            val newEntries = marketHandler.getEntries().filter { it.expire.isAfter(LocalDateTime.now()) }
            maxPage = newEntries.size / 45

            val itemsOnDisplay = newEntries.subList(page * 45, Integer.min((page + 1) * 45, newEntries.size))
            itemsOnDisplay.forEachIndexed { index, storedMarketEntry ->
                val timeLeft = Duration.between(LocalDateTime.now(), storedMarketEntry.expire)
                val timeString = "${timeLeft.toDays()}d ${timeLeft.toHours() % 24}h " +
                        "${timeLeft.toMinutes() % 60}m ${timeLeft.seconds % 60}s"

                val tag = storedMarketEntry.item.nbt!!
                val display = tag.getCompound("display")
                val lore = display.getList("Lore", NbtType.STRING)

                // Remove existing lore
                val oldTags = mutableListOf<NbtElement>()
                for (x in 3 downTo 0) {
                    oldTags.add(lore.removeAt(x))
                }
                val newLore = NbtList()
                oldTags.reverse()
                oldTags.removeLast()
                newLore.addAll(oldTags)
                newLore.add(
                    NbtString.of("[{\"text\":\"Expires in: \",\"color\":\"white\",\"italic\":false},{\"text\":\"$timeString\",\"color\":\"gray\",\"italic\":false}]")
                )
                for (i in 0 until lore.lastIndex) {
                    newLore.add(lore[i])
                }
                display.put("Lore", newLore)
                tag.put("display", display)
                storedMarketEntry.item.nbt = tag

                this_.button(index % 9, index / 9, storedMarketEntry.item) { it, c ->
                    if (player.getPresentPermission("market.buy", 0)) {
                        buyItem(player, storedMarketEntry)
                    } else {
                        this_.close()
                        player.sendMessage(LiteralText("No permission to buy items!"), false)
                    }
                }
            }
            if (itemsOnDisplay.size < 45) {
                for (x in itemsOnDisplay.size until 45) {
                    this_.button(x % 9, x / 9, EMPTY) { it, c ->

                    }
                }
            }
        }

        val gui = ChestGui.factory {
            player(context.source.player)
            emptyIcon(EMPTY)

            refreshInterval(1) {
                reload(it)
            }

            // Navigation
            for (i in 0 until 9) {
                // Filler
                button(i, 5, ItemStack(Items.GREEN_STAINED_GLASS_PANE)) { it, c -> }
            }

            if (maxPage != 0) {
                button(0, 5, ItemStack(Items.BLUE_STAINED_GLASS_PANE).setCustomName(LiteralText("Previous page"))) { it, container ->
                    val prevPage = if (page == 0) maxPage else page - 1
                    page = prevPage
                    reload(container)
                }

                button(8, 5, ItemStack(Items.BLUE_STAINED_GLASS_PANE).setCustomName(LiteralText("Next page"))) { it, container ->
                    val nextPage = if (page == maxPage) 0 else page + 1
                    page = nextPage
                    reload(container)
                }
            }
        }

        player.openHandledScreen(object : NamedScreenHandlerFactory {
            override fun createMenu(syncId: Int, inv: PlayerInventory, p: PlayerEntity): ScreenHandler {
                return gui.invoke(syncId, p as ServerPlayerEntity)
            }

            override fun getDisplayName(): Text {
                return LiteralText("Market")
            }

        })
    }

    private fun buyItem(player: ServerPlayerEntity, entry: StoredMarketEntry) {
        val balance = balanceHandler.getUser(player.uuid).balance

        // Check if user has enough money
        if (balance < entry.price) {
            player.sendMessage(LiteralText("Not enough money!"), false)
        } else {
            // Check if still present
            if (marketHandler.getEntries().any { it.expire == entry.expire }) {
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
                val tkey = item.translationKey
                val amount = item.count
                val tag = item.nbt!!
                val display = tag.getCompound("display")
                val lore = display.getList("Lore", NbtType.STRING) as NbtList

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
                    item.nbt = null
                } else {
                    item.nbt = tag
                }

                // Give item
                if (player.giveItemStack(item)) {
                    // Unable to insert
                    ItemScatterer.spawn(player.world, player.x, player.y, player.z, item)
                }

                player.sendMessage(
                        LiteralText("Successfully purchased item!"),
                        false)
                val p = player.server.playerManager.getPlayer(entry.uuid) ?: return
                val sidebar = SidebarInfo.factory {
                    displayTitle(LiteralText("Item sold!"))
                    line("One of your items was sold!")
                    line("")

                    line("Item: ${amount}x ${TranslatedText(tkey).translateForPlayer(p)}", Formatting.GREEN)
                    line("Price: ${entry.price}", Formatting.BLUE)
                }(p)

                sidebar.removeAfter(5)

            } else {
                player.sendMessage(LiteralText("Item no longer available"), false)
            }
        }
    }
}
