package io.github.gunpowder.entities

import net.minecraft.item.ItemStack
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.*

class StoredMarketEntry(val uuid: UUID, val item: ItemStack, val price: BigDecimal, val expire: LocalDateTime)
