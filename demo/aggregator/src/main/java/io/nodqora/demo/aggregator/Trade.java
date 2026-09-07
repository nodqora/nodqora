// SPDX-License-Identifier: Apache-2.0
package io.nodqora.demo.aggregator;

/** One Coinbase trade, as it arrives on {@code market.trades.<product>}. */
public record Trade(String product, long tradeId, double price, double size, String side, String time) {}
