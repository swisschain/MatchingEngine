package com.lykke.matching.engine.messages

import java.util.HashMap

enum class MessageType (val type: Byte){
    RESPONSE(0)
    ,PING(1)
    ,UPDATE_BALANCE(2)
    ,LIMIT_ORDER(3)
    ,MARKET_ORDER(4)
    ,LIMIT_ORDER_CANCEL(5)
    ;


    companion object {
        val typesMap = HashMap<Byte, MessageType>()

        init {
            values().forEach {
                typesMap.put(it.type, it)
            }
        }

        fun valueOf(type: Byte): MessageType? {
            return typesMap[type]
        }
    }
}