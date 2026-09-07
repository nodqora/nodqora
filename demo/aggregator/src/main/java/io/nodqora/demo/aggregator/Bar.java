// SPDX-License-Identifier: Apache-2.0
package io.nodqora.demo.aggregator;

/**
 * A one-minute bar under construction, and the record written to {@code market.ohlc.1m}.
 *
 * <p>{@code maxTradeId} is the deduplication guard. Each poll of the Coinbase trades endpoint
 * returns the newest N trades, so consecutive polls overlap and the same trade is published more
 * than once. Trades reach this aggregate rekeyed by product and in ascending id order, so a record
 * whose id is not greater than the highest already folded in has been seen before.
 */
public record Bar(
        String product,
        long windowStart,
        long windowEnd,
        double open,
        double high,
        double low,
        double close,
        double volume,
        double notional,
        long trades,
        long maxTradeId) {

    public static Bar empty() {
        return new Bar(null, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    public boolean noTrades() {
        return trades == 0;
    }

    public Bar add(Trade trade) {
        if (trade.tradeId() <= maxTradeId) {
            return this;
        }
        return new Bar(
                trade.product(),
                windowStart,
                windowEnd,
                noTrades() ? trade.price() : open,
                noTrades() ? trade.price() : Math.max(high, trade.price()),
                noTrades() ? trade.price() : Math.min(low, trade.price()),
                trade.price(),
                volume + trade.size(),
                notional + trade.price() * trade.size(),
                trades + 1,
                Math.max(maxTradeId, trade.tradeId()));
    }

    public Bar withWindow(long startMillis, long endMillis) {
        return new Bar(product, startMillis, endMillis, open, high, low, close, volume, notional, trades, maxTradeId);
    }

    public double vwap() {
        return volume == 0 ? close : notional / volume;
    }
}
