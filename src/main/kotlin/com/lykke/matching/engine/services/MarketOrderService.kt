package com.lykke.matching.engine.services

import com.lykke.matching.engine.balance.BalanceException
import com.lykke.matching.engine.daos.*
import com.lykke.matching.engine.matching.MatchingEngine
import com.lykke.matching.engine.messages.MessageStatus
import com.lykke.matching.engine.messages.MessageType
import com.lykke.matching.engine.messages.MessageWrapper
import com.lykke.matching.engine.messages.ProtocolMessages
import com.lykke.matching.engine.order.OrderStatus
import com.lykke.matching.engine.order.OrderStatus.InvalidFee
import com.lykke.matching.engine.order.OrderStatus.InvalidValue
import com.lykke.matching.engine.order.OrderStatus.InvalidVolume
import com.lykke.matching.engine.order.OrderStatus.InvalidVolumeAccuracy
import com.lykke.matching.engine.order.OrderStatus.LeadToNegativeSpread
import com.lykke.matching.engine.order.OrderStatus.Matched
import com.lykke.matching.engine.order.OrderStatus.NoLiquidity
import com.lykke.matching.engine.order.OrderStatus.NotEnoughFunds
import com.lykke.matching.engine.order.OrderStatus.ReservedVolumeGreaterThanBalance
import com.lykke.matching.engine.order.OrderStatus.TooHighPriceDeviation
import com.lykke.matching.engine.services.validators.impl.OrderValidationException
import com.lykke.matching.engine.outgoing.messages.MarketOrderWithTrades
import com.lykke.matching.engine.utils.PrintUtils
import com.lykke.matching.engine.utils.NumberUtils
import com.lykke.matching.engine.utils.order.MessageStatusUtils
import com.lykke.matching.engine.holders.MessageSequenceNumberHolder
import com.lykke.matching.engine.order.process.StopOrderBookProcessor
import com.lykke.matching.engine.order.process.common.MatchingResultHandlingHelper
import com.lykke.matching.engine.order.process.context.MarketOrderExecutionContext
import com.lykke.matching.engine.order.transaction.ExecutionContextFactory
import com.lykke.matching.engine.order.ExecutionDataApplyService
import com.lykke.matching.engine.daos.context.MarketOrderContext
import com.lykke.matching.engine.outgoing.messages.v2.builders.EventFactory
import com.lykke.matching.engine.services.validators.business.MarketOrderBusinessValidator
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import java.util.Date
import java.util.concurrent.BlockingQueue

@Service
class MarketOrderService @Autowired constructor(
        private val matchingEngine: MatchingEngine,
        private val executionContextFactory: ExecutionContextFactory,
        private val stopOrderBookProcessor: StopOrderBookProcessor,
        private val executionDataApplyService: ExecutionDataApplyService,
        private val matchingResultHandlingHelper: MatchingResultHandlingHelper,
        private val genericLimitOrderService: GenericLimitOrderService,
        private val rabbitSwapQueue: BlockingQueue<MarketOrderWithTrades>,
        private val messageSequenceNumberHolder: MessageSequenceNumberHolder,
        private val marketOrderBusinessValidator: MarketOrderBusinessValidator,
        private val messageSender: MessageSender) : AbstractService {
    companion object {
        private val LOGGER = LoggerFactory.getLogger(MarketOrderService::class.java.name)
        private val STATS_LOGGER = LoggerFactory.getLogger("${MarketOrderService::class.java.name}.stats")
    }

    private var messagesCount: Long = 0
    private var logCount = 100
    private var totalTime: Double = 0.0

    override fun processMessage(messageWrapper: MessageWrapper) {
        val startTime = System.nanoTime()
        val now = Date()

        val context = messageWrapper.context as MarketOrderContext
        val order = context.marketOrder
        order.register(now)

        val assetPair = context.assetPair

        LOGGER.debug("Got market order messageId: ${messageWrapper.messageId}, " +
                "id: ${messageWrapper.id}, client: ${order.clientId}, " +
                "asset: ${order.assetPairId}, volume: ${NumberUtils.roundForPrint(order.volume)}, " +
                "straight: ${order.straight}, fee: $order.fee, fees: ${order.fees}")

        try {
            marketOrderBusinessValidator.performValidation(order)
        } catch (e: OrderValidationException) {
            order.updateStatus(e.orderStatus, now)
            sendErrorNotification(messageWrapper, order, now)
            LOGGER.info("Business validation failed for order: $order")
            writeErrorResponse(messageWrapper, order, e.message)
            return
        }

        val executionContext = executionContextFactory.create(messageWrapper.messageId!!,
                messageWrapper.id!!,
                MessageType.MARKET_ORDER,
                messageWrapper.processedMessage,
                mapOf(Pair(assetPair!!.assetPairId, assetPair)),
                now,
                LOGGER)

        val marketOrderExecutionContext = MarketOrderExecutionContext(order, executionContext)

        val matchingResult = matchingEngine.match(order,
                getOrderBook(order),
                messageWrapper.messageId!!,
                priceDeviationThreshold = assetPair.marketOrderPriceDeviationThreshold ?: context.marketPriceDeviationThreshold,
                executionContext = executionContext)
        marketOrderExecutionContext.matchingResult = matchingResult

        when (OrderStatus.valueOf(matchingResult.orderCopy.status)) {
            ReservedVolumeGreaterThanBalance,
            NoLiquidity,
            LeadToNegativeSpread,
            NotEnoughFunds,
            InvalidFee,
            InvalidVolumeAccuracy,
            InvalidVolume,
            InvalidValue,
            TooHighPriceDeviation -> {
                if (matchingResult.cancelledLimitOrders.isNotEmpty()) {
                    matchingResultHandlingHelper.preProcessCancelledOppositeOrders(marketOrderExecutionContext)
                    matchingResultHandlingHelper.preProcessCancelledOrdersWalletOperations(marketOrderExecutionContext)
                    matchingResultHandlingHelper.processCancelledOppositeOrders(marketOrderExecutionContext)
                    val orderBook = marketOrderExecutionContext.executionContext.orderBooksHolder
                            .getChangedOrderBookCopy(marketOrderExecutionContext.order.assetPairId)
                    matchingResult.cancelledLimitOrders.forEach {
                        orderBook.removeOrder(it.origin!!)
                    }
                }
                marketOrderExecutionContext.executionContext.marketOrderWithTrades = MarketOrderWithTrades(executionContext.messageId, order)
            }
            Matched -> {
                if (matchingResult.cancelledLimitOrders.isNotEmpty()) {
                    matchingResultHandlingHelper.preProcessCancelledOppositeOrders(marketOrderExecutionContext)
                }
                if (matchingResult.uncompletedLimitOrderCopy != null) {
                    matchingResultHandlingHelper.preProcessUncompletedOppositeOrder(marketOrderExecutionContext)
                }
                marketOrderExecutionContext.ownWalletOperations = matchingResult.ownCashMovements
                val preProcessResult = try {
                    matchingResultHandlingHelper.processWalletOperations(marketOrderExecutionContext)
                    true
                } catch (e: BalanceException) {
                    order.updateStatus(OrderStatus.NotEnoughFunds, now)
                    marketOrderExecutionContext.executionContext.marketOrderWithTrades = MarketOrderWithTrades(messageWrapper.messageId!!, order)
                    LOGGER.error("$order: Unable to process wallet operations after matching: ${e.message}")
                    false
                }

                if (preProcessResult) {
                    matchingResult.apply()
                    executionContext.orderBooksHolder.addCompletedOrders(matchingResult.completedLimitOrders.map { it.origin!! })

                    if (matchingResult.cancelledLimitOrders.isNotEmpty()) {
                        matchingResultHandlingHelper.processCancelledOppositeOrders(marketOrderExecutionContext)
                    }
                    if (matchingResult.uncompletedLimitOrderCopy != null) {
                        matchingResultHandlingHelper.processUncompletedOppositeOrder(marketOrderExecutionContext)
                    }

                    matchingResult.skipLimitOrders.forEach { matchingResult.orderBook.put(it) }

                    marketOrderExecutionContext.executionContext.orderBooksHolder
                            .getChangedOrderBookCopy(order.assetPairId)
                            .setOrderBook(!order.isBuySide(), matchingResult.orderBook)
                    marketOrderExecutionContext.executionContext.lkkTrades.addAll(matchingResult.lkkTrades)

                    marketOrderExecutionContext.executionContext.marketOrderWithTrades = MarketOrderWithTrades(messageWrapper.messageId!!, order, matchingResult.marketOrderTrades)
                    matchingResult.limitOrdersReport?.orders?.let { marketOrderExecutionContext.executionContext.addClientsLimitOrdersWithTrades(it) }
                }
            }
            else -> {
                executionContext.error("Not handled order status: ${matchingResult.orderCopy.status}")
            }
        }

        stopOrderBookProcessor.checkAndExecuteStopLimitOrders(executionContext)
        val persisted = executionDataApplyService.persistAndSendEvents(messageWrapper, executionContext)
        if (!persisted) {
            writePersistenceErrorResponse(messageWrapper, order)
            return
        }

        writeResponse(messageWrapper, order, MessageStatusUtils.toMessageStatus(order.status))

        val endTime = System.nanoTime()

        messagesCount++
        totalTime += (endTime - startTime).toDouble() / logCount

        if (messagesCount % logCount == 0L) {
            STATS_LOGGER.info("Total: ${PrintUtils.convertToString(totalTime)}. ")
            totalTime = 0.0
        }
    }

    private fun getOrderBook(order: MarketOrder) =
            genericLimitOrderService.getOrderBook(order.assetPairId).getOrderBook(!order.isBuySide())

    private fun writePersistenceErrorResponse(messageWrapper: MessageWrapper, order: MarketOrder) {
        val message = "Unable to save result data"
        LOGGER.error("$order: $message")
        writeResponse(messageWrapper, order, MessageStatus.RUNTIME, message)
        return
    }

    private fun writeResponse(messageWrapper: MessageWrapper, order: MarketOrder, status: MessageStatus, reason: String? = null) {
        val marketOrderResponse = ProtocolMessages.MarketOrderResponse.newBuilder()
                .setStatus(status.type)
        if (order.price != null) {
            marketOrderResponse.price = order.price!!.toDouble()
        } else if (reason != null) {
            marketOrderResponse.statusReason = reason
        }
        messageWrapper.writeMarketOrderResponse(marketOrderResponse)
    }

    private fun writeErrorResponse(messageWrapper: MessageWrapper,
                                   order: MarketOrder,
                                   statusReason: String? = null) {
        writeResponse(messageWrapper, order, MessageStatusUtils.toMessageStatus(order.status), statusReason)
    }

    private fun sendErrorNotification(messageWrapper: MessageWrapper,
                                      order: MarketOrder,
                                      now: Date) {
        val marketOrderWithTrades = MarketOrderWithTrades(messageWrapper.messageId!!, order)
        rabbitSwapQueue.put(marketOrderWithTrades)
        val outgoingMessage = EventFactory.createExecutionEvent(messageSequenceNumberHolder.getNewValue(),
                messageWrapper.messageId!!,
                messageWrapper.id!!,
                now,
                MessageType.MARKET_ORDER,
                marketOrderWithTrades)
        messageSender.sendMessage(outgoingMessage)
    }

    override fun parseMessage(messageWrapper: MessageWrapper) {
        //do nothing
    }

    override fun writeResponse(messageWrapper: MessageWrapper, status: MessageStatus) {
        messageWrapper.writeMarketOrderResponse(ProtocolMessages.MarketOrderResponse.newBuilder()
                .setStatus(status.type))
    }
}