package com.lykke.matching.engine.database

import com.lykke.matching.engine.daos.MarketOrder
import com.lykke.matching.engine.daos.Trade
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Date

class TestMarketOrderDatabaseAccessor : MarketOrderDatabaseAccessor {

    val DATE_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")

    val orders = ArrayList<MarketOrder>()
    val trades = ArrayList<Trade>()

    override fun addMarketOrder(order: MarketOrder) {
        orders.add(order)
    }

    override fun addMarketOrderWithGeneratedRowId(order: MarketOrder) {
        val orderClientTimeKey = MarketOrder(
                uid = Date().time.toString(),
                assetPairId = order.assetPairId,
                clientId = order.clientId,
                createdAt = order.createdAt,
                registered = Date(),
                status = order.status,
                volume = order.volume,
                matchedOrders = order.matchedOrders,
                price = order.price
        )
        orderClientTimeKey.partitionKey = order.clientId
        orderClientTimeKey.rowKey = "%s.#%02d".format(DATE_FORMAT.format(order.matchedAt), 0)
        orders.add(orderClientTimeKey)
    }

    override fun updateMarketOrder(order: MarketOrder) {
        //nothing to do, already in memory
    }

    override fun addTrades(trades: List<Trade>) {
        this.trades.addAll(trades)
    }

    fun getLastOrder() = orders.last()

    fun clear() = {
        orders.clear()
        trades.clear()
    }
}