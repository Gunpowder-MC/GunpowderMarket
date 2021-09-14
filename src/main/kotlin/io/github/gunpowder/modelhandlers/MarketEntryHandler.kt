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

package io.github.gunpowder.modelhandlers

import io.github.gunpowder.api.GunpowderMod
import io.github.gunpowder.entities.StoredMarketEntry
import io.github.gunpowder.models.MarketEntryTable
import net.minecraft.nbt.NbtCompound
import net.minecraft.nbt.NbtList
import net.minecraft.nbt.NbtString
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import java.time.Duration
import java.time.LocalDateTime

object MarketEntryHandler {
    private val db by lazy {
        GunpowderMod.instance.database
    }

    fun createEntry(e: StoredMarketEntry) {
        db.transaction {
            MarketEntryTable.insert {
                it[user] = e.uuid
                it[item] = e.item
                it[price] = e.price
                it[expiresAt] = e.expire
            }
        }
    }

    fun getEntries(): List<StoredMarketEntry> {
        return db.transaction {
            MarketEntryTable.selectAll().map {
                val entry = StoredMarketEntry(
                    it[MarketEntryTable.user],
                    it[MarketEntryTable.item],
                    it[MarketEntryTable.price],
                    it[MarketEntryTable.expiresAt]
                )
                val seller = GunpowderMod.instance.server.userCache.getByUuid(entry.uuid).orElse(null)?.name ?: "DummySeller"
                val timeLeft = Duration.between(LocalDateTime.now(), entry.expire)
                val timeString = "${timeLeft.toDays()}d ${timeLeft.toHours() % 24}h " +
                        "${timeLeft.toMinutes() % 60}m ${timeLeft.seconds % 60}s"

                // Add Lore
                val tag = entry.item.nbt ?: NbtCompound()
                val display = tag.get("display") as NbtCompound? ?: NbtCompound()
                val lore = tag.get("Lore") as NbtList? ?: NbtList()
                val newLore = NbtList()

                newLore.addAll(
                        // Add our stuff
                        listOf(
                                NbtString.of("[{\"text\":\"\"}]"),  // Blank line
                                // Seller
                            NbtString.of("[{\"text\":\"Seller: \",\"color\":\"white\",\"italic\":false},{\"text\":\"$seller\",\"color\":\"green\",\"italic\":false}]"),
                                // Price
                            NbtString.of("[{\"text\":\"Price: \",\"color\":\"white\",\"italic\":false},{\"text\":\"$${entry.price}\",\"color\":\"gold\",\"italic\":false}]"),
                                // Expire time
                            NbtString.of("[{\"text\":\"Expires in: \",\"color\":\"white\",\"italic\":false},{\"text\":\"$timeString\",\"color\":\"gray\",\"italic\":false}]")
                        )
                )

                // Add original
                for (i in 0 until lore.lastIndex) {
                    newLore.add(lore[i])
                }

                display.put("Lore", newLore)
                tag.put("display", display)
                entry.item.nbt = tag
                entry
            }
        }.get()
    }

    fun deleteEntry(e: StoredMarketEntry) {
        db.transaction {
            MarketEntryTable.deleteWhere {
                MarketEntryTable.expiresAt.eq(e.expire)
            }
        }
    }
}
